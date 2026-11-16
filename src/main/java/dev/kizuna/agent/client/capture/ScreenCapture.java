package dev.kizuna.agent.client.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.kizuna.agent.bridge.BridgeException;
import dev.kizuna.agent.client.capture.CameraShots.Plan;
import dev.kizuna.agent.client.capture.CameraShots.Pose;
import dev.kizuna.agent.client.capture.CameraShots.Shot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 截游戏画面给助手看：玩家现在的视角，或者从指定的机位。
 *
 * <p>不动玩家，也不改任何游戏设置：指定机位时只在取帧的那几帧里把相机挪过去（相机那头由 mixin 来问 {@link #pose}），
 * 等那个位置看得见的区块都画出来再取，取完相机自然回到玩家身上。第三人称、隐藏 HUD、暂停这些状态都不碰，所以也不用还原。
 * 只截世界的图在世界画完、HUD 和界面还没画的时候取；连 HUD 一起的在整帧画完时取。</p>
 *
 * <p>请求来自桥接的工作线程：排进队列，渲染线程一帧帧推进，取到的像素回到工作线程上缩小、压缩、存盘。</p>
 */
public final class ScreenCapture {
    /** 一次请求在渲染线程上的进度。 */
    private static final class Job {
        private final List<Shot> shots;
        private final int[][] pixels;
        private final int[][] sizes;
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private int index;
        private int frames;
        private int settledFrames;
        private long shotStarted;
        private int arrived;
        private volatile boolean cancelled;

        private Job(List<Shot> shots) {
            this.shots = shots;
            this.pixels = new int[shots.size()][];
            this.sizes = new int[shots.size()][];
        }
    }

    /** 相机挪到新机位后，至少再画这么多帧才取。 */
    private static final int MIN_FRAMES = 4;
    /** 区块编译队列连着这么多帧是空的，才算那个机位看得见的都画出来了。 */
    private static final int SETTLED_FRAMES = 3;
    private static final long MIN_SHOT_MILLIS = 150;
    /** 一个机位最多等这么久，到了还有区块没画完也照取。 */
    private static final long MAX_SHOT_MILLIS = 3000;
    private static final int BASE_WAIT_SECONDS = 10;
    private static final int WAIT_SECONDS_PER_SHOT = 4;
    private static final int VALIDATE_SECONDS = 10;
    private static final double BLOCKS_PER_CHUNK = 16;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Deque<Job> QUEUE = new ConcurrentLinkedDeque<>();
    /** 只在渲染线程上碰。 */
    private static Job current;

    private ScreenCapture() {
        // 只有静态入口。
    }

    /**
     * 桥接接口 screenshot：照请求拍，返回每张图（base64）、存到哪儿、要留意的事。
     *
     * @param directory 图片存到哪个目录
     */
    public static JsonObject call(JsonObject request, Path directory) {
        Plan plan = CameraShots.plan(request);
        Minecraft minecraft = Minecraft.getInstance();
        List<List<String>> notes = await(minecraft.submit(() -> inspect(minecraft, plan)), VALIDATE_SECONDS, null);
        var job = new Job(plan.shots());
        QUEUE.add(job);
        await(job.done, BASE_WAIT_SECONDS + WAIT_SECONDS_PER_SHOT * plan.shots().size(), job);
        var answer = new JsonObject();
        var shots = new JsonArray();
        String stamp = LocalDateTime.now().format(STAMP);
        for (int index = 0; index < plan.shots().size(); index++) {
            ImageEncoder.Encoded image = ImageEncoder.encode(job.pixels[index], job.sizes[index][0], job.sizes[index][1], plan.width());
            Path file = directory.resolve("shot-" + stamp + (plan.shots().size() > 1 ? "-" + (index + 1) : "") + "." + image.extension());
            try {
                Files.createDirectories(directory);
                Files.write(file, image.bytes());
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
            var shot = new JsonObject();
            shot.addProperty("label", plan.shots().get(index).label());
            shot.addProperty("file", file.toString());
            shot.addProperty("width", image.width());
            shot.addProperty("height", image.height());
            shot.addProperty("mime", image.mime());
            shot.addProperty("data", Base64.getEncoder().encodeToString(image.bytes()));
            var remarks = new JsonArray();
            notes.get(index).forEach(remarks::add);
            shot.add("notes", remarks);
            shots.add(shot);
        }
        answer.add("shots", shots);
        return answer;
    }

    /** 这一帧相机该在的机位；没在拍指定机位的图时为空。相机每帧摆好位置后来问。 */
    public static Optional<Pose> pose() {
        Job job = current;
        return job == null ? Optional.empty() : job.shots.get(job.index).pose();
    }

    /** 这一帧相机被挪到了别处：手、准星指着的方块的描边都不该画。 */
    public static boolean posed() {
        return pose().isPresent();
    }

    /** 每帧开头（摆相机之前）调：手上没有请求就从队列里取下一个。 */
    public static void beginFrame() {
        while (current == null || current.cancelled) {
            current = QUEUE.poll();
            if (current == null) {
                return;
            }
            current.shotStarted = System.currentTimeMillis();
        }
    }

    /** 世界画完、HUD 还没画时调：只截世界的图在这儿取。 */
    public static void levelRendered() {
        if (current != null && !current.shots.get(current.index).hud()) {
            advance();
        }
    }

    /** 整帧（含 HUD 和界面）画完时调：连 HUD 一起的图在这儿取。 */
    public static void frameRendered() {
        if (current != null && current.shots.get(current.index).hud()) {
            advance();
        }
    }

    private static void advance() {
        Job job = current;
        if (job.cancelled) {
            current = null;
            return;
        }
        try {
            grabWhenReady(job);
        } catch (RuntimeException failed) {
            // 取帧出了岔子只算这次截图失败，不能把游戏的渲染线程带崩。
            current = null;
            job.done.completeExceptionally(failed);
        }
    }

    private static void grabWhenReady(Job job) {
        Minecraft minecraft = Minecraft.getInstance();
        job.frames++;
        job.settledFrames = minecraft.levelRenderer.hasRenderedAllSections() ? job.settledFrames + 1 : 0;
        long waited = System.currentTimeMillis() - job.shotStarted;
        boolean moved = job.shots.get(job.index).pose().isPresent();
        boolean ready = !moved || waited >= MAX_SHOT_MILLIS
                || job.frames >= MIN_FRAMES && job.settledFrames >= SETTLED_FRAMES && waited >= MIN_SHOT_MILLIS;
        if (!ready) {
            return;
        }
        int index = job.index;
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> arrived(job, index, image));
        if (index + 1 < job.shots.size()) {
            job.index++;
            job.frames = 0;
            job.settledFrames = 0;
            job.shotStarted = System.currentTimeMillis();
        } else {
            // 最后一张已经取了：下一帧起相机回到玩家身上；像素读回来以后这次请求才算完。
            current = null;
        }
    }

    private static void arrived(Job job, int index, NativeImage image) {
        try (image) {
            job.sizes[index] = new int[] {image.getWidth(), image.getHeight()};
            job.pixels[index] = image.getPixels();
        } catch (RuntimeException failed) {
            job.done.completeExceptionally(failed);
            return;
        }
        if (++job.arrived == job.shots.size()) {
            job.done.complete(null);
        }
    }

    /** 在客户端线程上看一遍各个机位：拍不了的直接拒，拍出来可能有问题的记下来随图说明。 */
    private static List<List<String>> inspect(Minecraft minecraft, Plan plan) {
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            throw BridgeException.unavailable("玩家还没进入世界，截不了图");
        }
        if (minecraft.getWindow().isMinimized()) {
            throw BridgeException.unavailable("游戏窗口最小化了，没在画画面，截不了图；让玩家把窗口切回来");
        }
        double reach = minecraft.options.getEffectiveRenderDistance() * BLOCKS_PER_CHUNK;
        plan.target().ifPresent(target -> {
            if (!loaded(level, target[0], target[2])) {
                throw BridgeException.unavailable("要围着看的点 " + describe(target) + " 所在的区块客户端没加载（玩家在 "
                        + player.blockPosition().toShortString() + "，渲染距离 " + (int) reach + " 格）。让玩家走近再截");
            }
        });
        var notes = new ArrayList<List<String>>();
        for (Shot shot : plan.shots()) {
            var remarks = new ArrayList<String>();
            shot.pose().ifPresent(pose -> remarks.addAll(inspect(level, player, pose, reach)));
            notes.add(List.copyOf(remarks));
        }
        return notes;
    }

    private static List<String> inspect(ClientLevel level, LocalPlayer player, Pose pose, double reach) {
        double[] position = {pose.x(), pose.y(), pose.z()};
        double away = Math.sqrt(Mth.square(pose.x() - player.getX()) + Mth.square(pose.z() - player.getZ()));
        if (away > reach) {
            throw BridgeException.unavailable("机位 " + describe(position) + " 离玩家 " + (int) away + " 格，超出渲染距离（" + (int) reach
                    + " 格）：那里的区块客户端没加载，截出来是空的。把机位放近些，或者让玩家走过去再截");
        }
        var remarks = new ArrayList<String>();
        if (!loaded(level, pose.x(), pose.z())) {
            remarks.add("机位所在的区块客户端还没加载，这张图可能缺一块甚至是空的");
        } else {
            BlockPos block = BlockPos.containing(pose.x(), pose.y(), pose.z());
            BlockState state = level.getBlockState(block);
            if (state.isSolidRender()) {
                remarks.add("机位在方块里面（" + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "），这张图会穿模；把机位挪到空气里重拍");
            }
        }
        if (away > reach * 0.75) {
            remarks.add("机位离玩家 " + (int) away + " 格，接近渲染距离的边缘，远处那一侧可能没有地形");
        }
        return remarks;
    }

    private static boolean loaded(ClientLevel level, double x, double z) {
        return level.getChunkSource().hasChunk(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
    }

    private static String describe(double[] point) {
        return "[" + (int) Math.floor(point[0]) + ", " + (int) Math.floor(point[1]) + ", " + (int) Math.floor(point[2]) + "]";
    }

    private static <T> T await(CompletableFuture<T> pending, int seconds, Job job) {
        try {
            return pending.get(seconds, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof BridgeException rejected) {
                throw rejected;
            }
            throw new IllegalStateException("截图没成：" + failed.getCause(), failed.getCause());
        } catch (TimeoutException slow) {
            if (job != null) {
                job.cancelled = true;
            }
            throw new BridgeException(504, "timeout", "等了 " + seconds + " 秒没截到图：游戏可能没在画画面（窗口最小化、正在加载）。稍后再试");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (job != null) {
                job.cancelled = true;
            }
            throw BridgeException.unavailable("桥接接口正在关闭");
        }
    }
}
