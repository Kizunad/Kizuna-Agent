package dev.kizuna.agent.clienttest;

/** 验收里「另一个也改相机的模组」的开关，见 {@code mixin/ForeignCameraMixin}。 */
public final class ForeignCamera {
    /** 打开后，相机对位那一步被提前结束。 */
    public static volatile boolean active;

    private ForeignCamera() {
        // 只有一个开关。
    }
}
