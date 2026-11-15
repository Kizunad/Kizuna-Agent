package dev.kizuna.agent.config;

import java.util.Map;

/**
 * 一个能切换到的模型：配置里模型清单的一项，或者不在清单里、直接按模型 ID 用 {@code [model]} 那份接口的模型。
 *
 * @param alias 游戏里叫它的名字；不在清单里的模型就是它的 ID
 * @param id 发给接口的模型名
 * @param thinking 这个模型的默认思考档位，已经落在 {@link #levels} 里
 * @param levels 这个模型认的思考档位 → 这一档发给接口的值，从少到多；空表表示思考档位由模型名决定、不发参数
 * @param listed 在配置的模型清单里
 */
public record ModelChoice(String alias, String id, String api, String baseUrl, String apiKey, String thinking,
                          Map<String, String> levels, int contextWindow, boolean listed) {
    /** 思考档位能调。 */
    public boolean adjustable() {
        return !levels.isEmpty();
    }

    /** 给人看的名字：别名和模型 ID 不一样时两个都写。 */
    public String label() {
        return alias.equals(id) ? id : alias + "（" + id + "）";
    }

    /** 同一个接口地址、协议、密钥的模型共用 pi 的一个 provider。 */
    public boolean sameEndpoint(ModelChoice other) {
        return api.equals(other.api) && baseUrl.equals(other.baseUrl) && apiKey.equals(other.apiKey);
    }

    @Override
    public String toString() {
        return "ModelChoice[alias=" + alias + ", id=" + id + ", api=" + api + ", baseUrl=" + baseUrl + ", apiKey="
                + (apiKey.isBlank() ? "未填" : "已填") + ", thinking=" + thinking + ", levels=" + levels + "]";
    }
}
