package dev.kizuna.agent.mixin.client;

import dev.kizuna.agent.client.capture.ScreenCapture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 给助手截图的两个取帧点（世界画完、整帧画完），和相机被挪走的那几帧里不该画的东西。 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Shadow
    @Final
    private GameRenderState gameRenderState;

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void kizunaAgent$levelRendered(DeltaTracker deltaTracker, CallbackInfo info) {
        ScreenCapture.levelRendered();
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void kizunaAgent$frameRendered(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo info) {
        ScreenCapture.frameRendered();
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void kizunaAgent$noHandFromElsewhere(CameraRenderState cameraState, float deltaPartialTick, Matrix4fc modelViewMatrix,
                                                 CallbackInfo info) {
        // 相机不在玩家眼睛里时，第一人称的手不该飘在画面上。
        if (ScreenCapture.posed()) {
            info.cancel();
        }
    }

    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void kizunaAgent$noOutlineFromElsewhere(CallbackInfoReturnable<Boolean> info) {
        if (ScreenCapture.posed()) {
            info.setReturnValue(false);
        }
    }

    @Inject(method = "extractOptions", at = @At("TAIL"))
    private void kizunaAgent$noFadeForScreenshot(CallbackInfo info) {
        // 新画出来的区块本来会慢慢淡入；截图等不了那么久，这几帧里直接画实。
        if (ScreenCapture.posed()) {
            gameRenderState.optionsRenderState.chunkSectionFadeInTime = 0;
        }
    }
}
