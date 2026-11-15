package dev.kizuna.agent.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 桥接接口的实现：替一个玩家在世界里执行指令、读写方块。
 *
 * <p>请求来自 HTTP 工作线程；凡是碰世界的都排到服务端主线程上做。「那个玩家」和他算不算世界主人由宿主给：
 * 单人世界里是开这个世界的人，按世界主人的权限执行，不受作弊开关限制；别的玩家照自己的权限。</p>
 */
public final class Bridge implements AutoCloseable {
    /** 单次读区域的体积上限（64×64×64）。 */
    static final int MAX_READ = 64 * 64 * 64;
    /** 准星最远看多少格。 */
    private static final double LOOK_DISTANCE = 128;
    private static final int COMMAND_SECONDS = 30;
    private static final int EDIT_SECONDS = 600;

    private final MinecraftServer server;
    private final Supplier<Optional<ServerPlayer>> actor;
    private final Predicate<ServerPlayer> owner;
    private final BlockEdits edits;

    public Bridge(MinecraftServer server, Supplier<Optional<ServerPlayer>> actor, Predicate<ServerPlayer> owner,
                  int millisPerTick) {
        this.server = server;
        this.actor = actor;
        this.owner = owner;
        this.edits = new BlockEdits(millisPerTick);
    }

    /** 碰世界的接口，名字到实现。 */
    public Map<String, BridgeServer.Call> calls() {
        var calls = new LinkedHashMap<String, BridgeServer.Call>();
        calls.put("command", this::command);
        calls.put("player", request -> onServer(this::describePlayer, COMMAND_SECONDS));
        calls.put("blocks/get", this::getBlocks);
        calls.put("blocks/set", this::setBlocks);
        calls.put("blocks/fill", this::fill);
        calls.put("blocks/clone", this::cloneBlocks);
        calls.put("undo", this::undo);
        return calls;
    }

    /** 服务端每 tick 调一次：推进排着队的批量改方块。 */
    public void tick() {
        edits.tick();
    }

    @Override
    public void close() {
        edits.close();
    }

    private JsonObject command(JsonObject request) {
        String command = Requests.text(request, "command").strip();
        if (command.isEmpty()) {
            throw BridgeException.badRequest("command 是空的");
        }
        return onServer(() -> run(command), COMMAND_SECONDS);
    }

    private JsonObject run(String command) {
        // 输出不进玩家聊天栏，全部收回来交给调用方；成功与否和返回值取指令自己报的。
        ServerPlayer player = requirePlayer();
        var lines = new JsonArray();
        boolean[] succeeded = {false};
        int[] value = {0};
        var capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                lines.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack source = player.createCommandSourceStack().withSource(capture).withPermission(rights(player))
                .withCallback((success, result) -> {
                    succeeded[0] = success;
                    value[0] = result;
                });
        server.getCommands().performPrefixedCommand(source, command);
        var answer = new JsonObject();
        answer.addProperty("success", succeeded[0]);
        answer.addProperty("result", value[0]);
        answer.add("output", lines);
        return answer;
    }

    private JsonObject describePlayer() {
        ServerPlayer player = requirePlayer();
        var answer = new JsonObject();
        answer.addProperty("name", player.getScoreboardName());
        answer.addProperty("dimension", player.level().dimension().identifier().toString());
        var position = new JsonArray();
        position.add(player.getX());
        position.add(player.getY());
        position.add(player.getZ());
        answer.add("position", position);
        answer.add("block", Requests.position(player.blockPosition()));
        answer.addProperty("yaw", player.getYRot());
        answer.addProperty("pitch", player.getXRot());
        answer.addProperty("facing", player.getDirection().getName());
        answer.addProperty("gamemode", player.gameMode().getName());
        answer.addProperty("holding", BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString());
        answer.addProperty("can_edit", canEdit(player));
        HitResult hit = player.pick(LOOK_DISTANCE, 1.0F, false);
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            var looking = new JsonObject();
            looking.add("block", Requests.position(block.getBlockPos()));
            looking.addProperty("face", block.getDirection().getName());
            looking.addProperty("state", BlockPalette.write(player.level().getBlockState(block.getBlockPos())));
            looking.add("adjacent", Requests.position(block.getBlockPos().relative(block.getDirection())));
            answer.add("looking_at", looking);
        }
        return answer;
    }

    private JsonObject getBlocks(JsonObject request) {
        Box box = Box.of(Requests.position(request, "from"), Requests.position(request, "to"));
        if (box.volume() > MAX_READ) {
            throw BridgeException.badRequest("一次最多读 " + MAX_READ + " 个方块（64×64×64），这块有 " + box.volume() + " 个，分块读");
        }
        boolean withData = Requests.flag(request, "nbt", false);
        return onServer(() -> read(level(request), box, withData), COMMAND_SECONDS);
    }

    private JsonObject read(ServerLevel level, Box box, boolean withData) {
        // 下标顺序：先 y，再 z，再 x，即 index = (dy * 长z + dz) * 长x + dx。
        requireLoaded(level, box);
        var palette = new BlockPalette.Builder();
        var indices = new JsonArray();
        var entities = new JsonArray();
        var position = new BlockPos.MutableBlockPos();
        for (int y = box.min().getY(); y <= box.max().getY(); y++) {
            for (int z = box.min().getZ(); z <= box.max().getZ(); z++) {
                for (int x = box.min().getX(); x <= box.max().getX(); x++) {
                    position.set(x, y, z);
                    indices.add(palette.index(level.getBlockState(position)));
                    BlockEntity entity = withData ? level.getBlockEntity(position) : null;
                    if (entity != null) {
                        var entry = new JsonObject();
                        entry.add("position", Requests.position(position));
                        entry.addProperty("nbt", entity.saveWithoutMetadata(level.registryAccess()).toString());
                        entities.add(entry);
                    }
                }
            }
        }
        var answer = new JsonObject();
        answer.add("origin", Requests.position(box.min()));
        answer.add("size", Requests.position(box.size()));
        answer.add("palette", palette.names());
        answer.add("blocks", indices);
        if (withData) {
            answer.add("block_entities", entities);
        }
        return answer;
    }

    private JsonObject setBlocks(JsonObject request) {
        BlockPos origin = Requests.optionalPosition(request, "origin").orElse(BlockPos.ZERO);
        JsonArray blocks = Requests.array(request, "blocks");
        if (blocks.size() > BlockEdits.MAX_BATCH) {
            throw BridgeException.badRequest("一次最多放 " + BlockEdits.MAX_BATCH + " 个方块，这次有 " + blocks.size() + " 个，分批发");
        }
        List<BlockPalette.Entry> palette = BlockPalette.parse(blockLookup(), Requests.array(request, "palette"));
        long[] positions = new long[blocks.size()];
        int[] indices = new int[blocks.size()];
        for (int index = 0; index < positions.length; index++) {
            if (!blocks.get(index).isJsonArray() || blocks.get(index).getAsJsonArray().size() != 4) {
                throw BridgeException.badRequest("blocks 里每一项要是 [dx, dy, dz, 调色板下标]，第 " + index + " 项不是");
            }
            JsonArray block = blocks.get(index).getAsJsonArray();
            int entry = Requests.integer(block.get(3), "blocks");
            if (entry < 0 || entry >= palette.size()) {
                throw BridgeException.badRequest("blocks 第 " + index + " 项的调色板下标 " + entry + " 超出范围（调色板有 " + palette.size() + " 项）");
            }
            positions[index] = BlockPos.asLong(origin.getX() + Requests.integer(block.get(0), "blocks"),
                    origin.getY() + Requests.integer(block.get(1), "blocks"),
                    origin.getZ() + Requests.integer(block.get(2), "blocks"));
            indices[index] = entry;
        }
        return edit(request, level -> new BlockEdits.Batch(level, positions, indices, palette, mode(request), null));
    }

    private JsonObject fill(JsonObject request) {
        Box box = Box.of(Requests.position(request, "from"), Requests.position(request, "to"));
        if (box.volume() > BlockEdits.MAX_BATCH) {
            throw BridgeException.badRequest("一次最多填 " + BlockEdits.MAX_BATCH + " 个方块，这块有 " + box.volume() + " 个，分块填");
        }
        HolderLookup<Block> lookup = blockLookup();
        BlockPalette.Entry block = BlockPalette.parse(lookup, Requests.text(request, "block"));
        // replace 只按方块种类比，不看属性：「把这里的石头都换掉」。
        Predicate<BlockState> only = Requests.optionalText(request, "replace")
                .map(name -> BlockPalette.parse(lookup, name).state().getBlock())
                .<Predicate<BlockState>>map(replaced -> state -> state.is(replaced)).orElse(null);
        boolean hollow = Requests.flag(request, "hollow", false);
        long[] positions = hollow ? box.shell() : box.positions();
        return edit(request, level -> new BlockEdits.Batch(level, positions, new int[positions.length], List.of(block),
                mode(request), only));
    }

    private JsonObject cloneBlocks(JsonObject request) {
        Box source = Box.of(Requests.position(request, "from"), Requests.position(request, "to"));
        if (source.volume() > MAX_READ) {
            throw BridgeException.badRequest("一次最多复制 " + MAX_READ + " 个方块，这块有 " + source.volume() + " 个，分块复制");
        }
        BlockPos destination = Requests.position(request, "destination");
        BlockPos offset = destination.subtract(source.min());
        // 先在主线程上把源区域整块读下来，再排队放到目标处：两块重叠也不会读到刚放的。
        return edit(request, level -> {
            requireLoaded(level, source);
            long[] positions = source.positions();
            int[] indices = new int[positions.length];
            var palette = new ArrayList<BlockPalette.Entry>();
            var known = new HashMap<BlockState, Integer>();
            var position = new BlockPos.MutableBlockPos();
            for (int index = 0; index < positions.length; index++) {
                position.set(positions[index]);
                BlockState state = level.getBlockState(position);
                BlockEntity entity = level.getBlockEntity(position);
                if (entity != null) {
                    indices[index] = palette.size();
                    palette.add(new BlockPalette.Entry(state, entity.saveWithoutMetadata(level.registryAccess())));
                } else {
                    indices[index] = known.computeIfAbsent(state, added -> {
                        palette.add(new BlockPalette.Entry(added, null));
                        return palette.size() - 1;
                    });
                }
                positions[index] = position.move(offset).asLong();
            }
            return new BlockEdits.Batch(level, positions, indices, palette, mode(request), null);
        });
    }

    private JsonObject undo(JsonObject request) {
        var answer = new JsonObject();
        answer.addProperty("restored", undoLast());
        answer.addProperty("remaining", edits.undoable());
        return answer;
    }

    /** 撤回最近一批放的方块，返回还原了几个；不能在服务端主线程上调（要等主线程把方块放回去）。 */
    public int undoLast() {
        onServer(() -> requireEditor(requirePlayer()), COMMAND_SECONDS);
        CompletableFuture<BlockEdits.Result> pending = edits.undo();
        if (pending == null) {
            throw BridgeException.unavailable("没有可以撤回的改动");
        }
        return await(pending, EDIT_SECONDS).changed();
    }

    private JsonObject edit(JsonObject request, Function<ServerLevel, BlockEdits.Batch> prepare) {
        // 权限和维度在主线程上查，批次也在主线程上备好再排队；放完才应答。
        CompletableFuture<BlockEdits.Result> pending = onServer(() -> {
            requireEditor(requirePlayer());
            return edits.submit(prepare.apply(level(request)));
        }, COMMAND_SECONDS);
        BlockEdits.Result result = await(pending, EDIT_SECONDS);
        var answer = new JsonObject();
        answer.addProperty("changed", result.changed());
        answer.addProperty("skipped", result.skipped());
        answer.addProperty("undoable", result.undoable());
        answer.addProperty("ticks", result.ticks());
        return answer;
    }

    private static BlockEdits.Mode mode(JsonObject request) {
        String name = Requests.optionalText(request, "mode").orElse("shapes");
        return switch (name) {
            case "shapes" -> BlockEdits.Mode.SHAPES;
            case "physics" -> BlockEdits.Mode.PHYSICS;
            case "strict" -> BlockEdits.Mode.STRICT;
            default -> throw BridgeException.badRequest("mode 只能是 shapes（默认）、physics、strict，收到 " + name);
        };
    }

    private ServerLevel level(JsonObject request) {
        Optional<String> name = Requests.optionalText(request, "dimension");
        if (name.isEmpty()) {
            return requirePlayer().level();
        }
        Identifier id = Identifier.tryParse(name.get());
        ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) {
            throw BridgeException.badRequest("没有这个维度：" + name.get());
        }
        return level;
    }

    private HolderLookup<Block> blockLookup() {
        return server.registryAccess().lookupOrThrow(Registries.BLOCK);
    }

    private static void requireLoaded(ServerLevel level, Box box) {
        // 没加载的区块不去读：读会把区块现生成出来，卡住主线程。
        for (int chunkX = box.min().getX() >> 4; chunkX <= box.max().getX() >> 4; chunkX++) {
            for (int chunkZ = box.min().getZ() >> 4; chunkZ <= box.max().getZ() >> 4; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    throw BridgeException.unavailable("区域里有没加载的区块（区块 " + chunkX + ", " + chunkZ + "），走近一些再读");
                }
            }
        }
    }

    private ServerPlayer requirePlayer() {
        return actor.get().orElseThrow(() -> BridgeException.unavailable("玩家还没进入世界"));
    }

    private PermissionSet rights(ServerPlayer player) {
        return owner.test(player) ? LevelBasedPermissionSet.OWNER : player.permissions();
    }

    private boolean canEdit(ServerPlayer player) {
        return rights(player).hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private JsonObject requireEditor(ServerPlayer player) {
        if (!canEdit(player)) {
            throw BridgeException.forbidden("这个玩家没有改方块的权限（需要 2 级管理员权限）");
        }
        return new JsonObject();
    }

    private <T> T onServer(Supplier<T> task, int seconds) {
        // 已经在主线程上就直接做（GameTest 里会这样调），否则排过去等结果。
        if (server.isSameThread()) {
            return task.get();
        }
        return await(server.submit(task), seconds);
    }

    private static <T> T await(CompletableFuture<T> pending, int seconds) {
        try {
            return pending.get(seconds, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof BridgeException rejected) {
                throw rejected;
            }
            if (failed.getCause() instanceof CancellationException) {
                throw BridgeException.unavailable("世界已关闭");
            }
            throw new IllegalStateException(failed.getCause());
        } catch (CancellationException cancelled) {
            throw BridgeException.unavailable("世界已关闭");
        } catch (TimeoutException slow) {
            throw new BridgeException(504, "timeout", "等了 " + seconds + " 秒还没做完");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw BridgeException.unavailable("桥接接口正在关闭");
        }
    }
}
