package dev.kizuna.agent.bridge;

/** 桥接接口拒绝一次请求：HTTP 状态码、给程序看的短码、给人和 agent 看的一句话。 */
public final class BridgeException extends RuntimeException {
    private final int status;
    private final String code;

    public BridgeException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static BridgeException badRequest(String message) {
        return new BridgeException(400, "bad_request", message);
    }

    public static BridgeException forbidden(String message) {
        return new BridgeException(403, "forbidden", message);
    }

    public static BridgeException unavailable(String message) {
        return new BridgeException(409, "unavailable", message);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
