package dev.kizuna.agent;

import dev.kizuna.agent.bridge.BridgeHost;
import dev.kizuna.agent.config.AgentConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 模组入口：单人世界开着时起桥接，关世界时收掉。
 *
 * <p>第一版只在单人世界（内置服务端）里起：替开世界的那个玩家执行，按世界主人的权限。专用服务器上不起。</p>
 */
public final class AgentMod implements ModInitializer {
    public static final String ID = "kizuna_agent";
    public static final Logger LOGGER = LoggerFactory.getLogger("kizuna-agent");
    private static final String DATA_DIRECTORY = "kizuna-agent";
    private static final String CONNECTION_FILE = "bridge.json";

    private static volatile BridgeHost host;
    private static volatile AgentConfig config;
    private static volatile String problem;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(AgentMod::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        ServerTickEvents.END_SERVER_TICK.register(BridgeHost::tick);
    }

    /** 实例里放本模组数据的目录：解出来的 pi、Python 库、会话、工作目录。 */
    public static Path dataDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve(DATA_DIRECTORY);
    }

    public static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(AgentConfig.FILE_NAME);
    }

    /** 当前世界的桥接；没开单人世界时为空。 */
    public static Optional<BridgeHost> host() {
        return Optional.ofNullable(host);
    }

    /** 这次进世界时读到的配置；配置文件有错时为空，错在哪儿见 {@link #problem}。 */
    public static Optional<AgentConfig> config() {
        return Optional.ofNullable(config);
    }

    /** 桥接没起来或配置读不了的原因，一句话；一切正常时为空。 */
    public static Optional<String> problem() {
        return Optional.ofNullable(problem);
    }

    private static void start(MinecraftServer server) {
        if (server.isDedicatedServer()) {
            return;
        }
        config = null;
        problem = null;
        try {
            AgentConfig loaded = AgentConfig.load(configFile());
            if (!loaded.missing().isEmpty()) {
                LOGGER.warn("{} 里缺这些键，先用默认值：{}", AgentConfig.FILE_NAME, String.join("、", loaded.missing()));
            }
            host = BridgeHost.open(server, () -> owner(server), player -> server.isSingleplayerOwner(player.nameAndId()),
                    loaded.bridge().port(), loaded.bridge().blocksPerTickMillis(), dataDirectory().resolve(CONNECTION_FILE));
            config = loaded;
            LOGGER.info("游戏内接口已就绪：{}", host.url());
        } catch (IllegalArgumentException invalid) {
            problem = invalid.getMessage();
            LOGGER.error("配置读不了，助手不可用：{}", problem);
        } catch (IOException | RuntimeException failed) {
            problem = "游戏内接口没起来：" + failed.getMessage();
            LOGGER.error("游戏内接口没起来", failed);
        }
    }

    private static Optional<ServerPlayer> owner(MinecraftServer server) {
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> server.isSingleplayerOwner(player.nameAndId())).findFirst();
    }

    private static void stop() {
        BridgeHost current = host;
        host = null;
        if (current != null) {
            current.close();
        }
    }
}
