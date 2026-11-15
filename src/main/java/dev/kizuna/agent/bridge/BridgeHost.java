package dev.kizuna.agent.bridge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 一个世界开着时的整套桥接：接口实现、HTTP 服务，和告诉外面「去哪儿连、令牌是什么」的那个文件。
 *
 * <p>令牌每次开世界重新随机生成；连接文件在关世界时删掉，留下来的旧文件连不上任何东西。</p>
 */
public final class BridgeHost implements AutoCloseable {
    private static final int TOKEN_BYTES = 32;
    /** 开着的各套桥接；服务端每 tick 推进属于它的那些。 */
    private static final Set<BridgeHost> OPEN = ConcurrentHashMap.newKeySet();

    private final MinecraftServer server;
    private final Bridge bridge;
    private final BridgeServer http;
    private final String token;
    private final Path connectionFile;

    private BridgeHost(MinecraftServer server, Bridge bridge, BridgeServer http, String token, Path connectionFile) {
        this.server = server;
        this.bridge = bridge;
        this.http = http;
        this.token = token;
        this.connectionFile = connectionFile;
    }

    /**
     * 起一套桥接。
     *
     * @param actor 替谁执行；玩家还没进世界时为空
     * @param owner 这个玩家算不算世界主人（按主人权限执行）
     * @param connectionFile 连接信息写到哪儿；为空则不写（测试用）
     */
    public static BridgeHost open(MinecraftServer server, Supplier<Optional<ServerPlayer>> actor,
                                  Predicate<ServerPlayer> owner, int port, int millisPerTick, Path connectionFile)
            throws IOException {
        byte[] random = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(random);
        String token = HexFormat.of().formatHex(random);
        var bridge = new Bridge(server, actor, owner, millisPerTick);
        var calls = new HashMap<>(bridge.calls());
        calls.putAll(new JarInspector(loadedMods()).calls());
        var http = new BridgeServer(port, token, calls);
        var host = new BridgeHost(server, bridge, http, token, connectionFile);
        if (connectionFile != null) {
            host.writeConnection();
        }
        OPEN.add(host);
        return host;
    }

    public MinecraftServer server() {
        return server;
    }

    public String url() {
        return http.url();
    }

    public String token() {
        return token;
    }

    /** 撤回最近一批放的方块，返回还原了几个；没有可撤的抛 {@link BridgeException}。不能在服务端主线程上调。 */
    public int undo() {
        return bridge.undoLast();
    }

    /** 服务端每 tick 调一次：推进这个服务端上各套桥接排着队的批量改方块。 */
    public static void tick(MinecraftServer server) {
        for (BridgeHost host : OPEN) {
            if (host.server == server) {
                host.bridge.tick();
            }
        }
    }

    @Override
    public void close() {
        OPEN.remove(this);
        http.close();
        bridge.close();
        if (connectionFile != null) {
            try {
                Files.deleteIfExists(connectionFile);
            } catch (IOException ignored) {
                // 删不掉也无妨：令牌已经作废。
            }
        }
    }

    private void writeConnection() throws IOException {
        var connection = new JsonObject();
        connection.addProperty("url", url());
        connection.addProperty("token", token);
        connection.addProperty("pid", ProcessHandle.current().pid());
        Files.createDirectories(connectionFile.getParent());
        Files.writeString(connectionFile, new GsonBuilder().setPrettyPrinting().create().toJson(connection) + "\n");
    }

    static List<JarInspector.Mod> loadedMods() {
        return FabricLoader.getInstance().getAllMods().stream().map(BridgeHost::describe)
                .sorted(Comparator.comparing(JarInspector.Mod::id)).toList();
    }

    private static JarInspector.Mod describe(ModContainer mod) {
        ModOrigin origin = mod.getOrigin();
        Optional<Path> jar = origin.getKind() == ModOrigin.Kind.PATH && !origin.getPaths().isEmpty()
                ? Optional.of(origin.getPaths().getFirst()) : Optional.empty();
        Optional<String> nestedIn = origin.getKind() == ModOrigin.Kind.NESTED
                ? Optional.of(origin.getParentModId()) : Optional.empty();
        return new JarInspector.Mod(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString(),
                mod.getMetadata().getName(), jar, nestedIn, mod.getRootPaths());
    }
}
