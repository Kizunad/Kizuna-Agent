package dev.kizuna.agent.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * 批量改方块：请求排成队，每个服务端 tick 只干限定毫秒数，干不完下个 tick 接着干，不卡服。
 *
 * <p>每一批改之前的样子（方块状态和箱子这类方块的内容）都记下来，最近若干批可以逐批撤回。
 * 被替换的方块不掉落、里面的东西不洒出来：撤回时原样放回，不会多出物品。</p>
 *
 * <p>只在服务端主线程上碰世界；{@link #submit} 可以从任何线程调。</p>
 */
final class BlockEdits {
    /** 怎么放：默认只联动相邻方块的形状（栅栏接上、楼梯拐角）；物理再加方块更新；原样则完全照给的状态放。 */
    enum Mode {
        SHAPES(Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS),
        PHYSICS(Block.UPDATE_ALL | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS),
        STRICT(Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS);

        private final int flags;

        Mode(int flags) {
            this.flags = flags;
        }
    }

    /** 一批要放的方块：位置、各自在调色板里的下标、调色板，和可选的「只替换这种」条件。 */
    record Batch(ServerLevel level, long[] positions, int[] indices, List<BlockPalette.Entry> palette, Mode mode,
                 Predicate<BlockState> only) {
    }

    /** 一批干完的结果：真改了几个、因为超出世界高度跳过几个、这一批还能不能撤回、分了几个 tick 放完。 */
    record Result(int changed, int skipped, boolean undoable, int ticks) {
    }

    /** 撤回用的记录：改过的位置和它们原来的样子。 */
    private record Undo(ServerLevel level, long[] positions, BlockState[] states, Map<Long, CompoundTag> entities) {
    }

    private static final class Job {
        private final Batch batch;
        private final boolean recorded;
        private final CompletableFuture<Result> done = new CompletableFuture<>();
        private final long[] positions;
        private final BlockState[] previous;
        private final Map<Long, CompoundTag> entities = new HashMap<>();
        private int next;
        private int changed;
        private int skipped;
        private int ticks;

        private Job(Batch batch, boolean recorded) {
            this.batch = batch;
            this.recorded = recorded;
            this.positions = new long[batch.positions().length];
            this.previous = new BlockState[batch.positions().length];
        }
    }

    static final int MAX_BATCH = 1_000_000;
    static final int HISTORY_BATCHES = 20;
    static final int HISTORY_BLOCKS = 4_000_000;
    /** 每放这么多个看一次表。 */
    private static final int CLOCK_STRIDE = 64;

    private final Deque<Job> queue = new ArrayDeque<>();
    private final Deque<Undo> history = new ArrayDeque<>();
    private final long budgetNanos;

    BlockEdits(int millisPerTick) {
        this.budgetNanos = Math.max(1, millisPerTick) * 1_000_000L;
    }

    CompletableFuture<Result> submit(Batch batch) {
        return enqueue(new Job(batch, true));
    }

    /** 撤回最近一批；没有可撤的返回空。 */
    synchronized CompletableFuture<Result> undo() {
        Undo last = history.pollLast();
        if (last == null) {
            return null;
        }
        var palette = new ArrayList<BlockPalette.Entry>(last.states().length);
        int[] indices = new int[last.states().length];
        for (int index = 0; index < indices.length; index++) {
            indices[index] = index;
            palette.add(new BlockPalette.Entry(last.states()[index], last.entities().get(last.positions()[index])));
        }
        // 撤回按原样放回，不触发任何联动：原来是什么样就是什么样。
        return enqueue(new Job(new Batch(last.level(), last.positions(), indices, palette, Mode.STRICT, null), false));
    }

    synchronized int undoable() {
        return history.size();
    }

    private synchronized CompletableFuture<Result> enqueue(Job job) {
        queue.addLast(job);
        return job.done;
    }

    /** 服务端每 tick 调一次。 */
    void tick() {
        long deadline = System.nanoTime() + budgetNanos;
        while (true) {
            Job job;
            synchronized (this) {
                job = queue.peekFirst();
            }
            if (job == null) {
                return;
            }
            try {
                if (!advance(job, deadline)) {
                    return;
                }
                finish(job);
            } catch (RuntimeException failed) {
                // 放到一半出错：已经放下去的照样记进撤回，再把错报给等着的请求。
                remember(job);
                job.done.completeExceptionally(failed);
            }
            synchronized (this) {
                queue.pollFirst();
            }
        }
    }

    /** 停服或退出世界：还没干的批次全部取消。 */
    synchronized void close() {
        for (Job job : queue) {
            job.done.completeExceptionally(new CancellationException("世界已关闭"));
        }
        queue.clear();
        history.clear();
    }

    private boolean advance(Job job, long deadline) {
        // 干到这一批完或者时间用完；返回这一批是否干完。
        job.ticks++;
        Batch batch = job.batch;
        ServerLevel level = batch.level();
        var position = new BlockPos.MutableBlockPos();
        while (job.next < batch.positions().length) {
            if (job.next % CLOCK_STRIDE == 0 && job.next > 0 && System.nanoTime() >= deadline) {
                return false;
            }
            int index = job.next++;
            position.set(batch.positions()[index]);
            if (level.isOutsideBuildHeight(position)) {
                job.skipped++;
                continue;
            }
            BlockPalette.Entry wanted = batch.palette().get(batch.indices()[index]);
            BlockState before = level.getBlockState(position);
            if (batch.only() != null && !batch.only().test(before)) {
                continue;
            }
            BlockEntity existing = level.getBlockEntity(position);
            if (before == wanted.state() && wanted.nbt() == null && existing == null) {
                continue;
            }
            CompoundTag saved = existing == null ? null : existing.saveWithFullMetadata(level.registryAccess());
            place(level, position, wanted, batch.mode());
            job.positions[job.changed] = batch.positions()[index];
            job.previous[job.changed] = before;
            if (saved != null) {
                job.entities.put(batch.positions()[index], saved);
            }
            job.changed++;
        }
        return true;
    }

    private static void place(ServerLevel level, BlockPos position, BlockPalette.Entry wanted, Mode mode) {
        // 先放方块，再把数据装进它的方块实体（箱子内容、告示牌文字），做法和 /setblock 带 NBT 时一样。
        level.setBlock(position, wanted.state(), mode.flags);
        if (wanted.nbt() == null) {
            return;
        }
        BlockEntity entity = level.getBlockEntity(position);
        if (entity != null) {
            entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), wanted.nbt()));
            entity.setChanged();
            level.sendBlockUpdated(position.immutable(), wanted.state(), wanted.state(), Block.UPDATE_CLIENTS);
        }
    }

    private void finish(Job job) {
        boolean undoable = remember(job);
        job.done.complete(new Result(job.changed, job.skipped, undoable, job.ticks));
    }

    private synchronized boolean remember(Job job) {
        if (!job.recorded || job.changed == 0) {
            return false;
        }
        long[] positions = Arrays.copyOf(job.positions, job.changed);
        BlockState[] states = Arrays.copyOf(job.previous, job.changed);
        history.addLast(new Undo(job.batch.level(), positions, states, job.entities));
        // 超过批数或总方块数就丢最早的；刚记的这一批总是留着。
        long total = history.stream().mapToLong(undo -> undo.positions().length).sum();
        while (history.size() > 1 && (history.size() > HISTORY_BATCHES || total > HISTORY_BLOCKS)) {
            total -= history.pollFirst().positions().length;
        }
        return true;
    }
}
