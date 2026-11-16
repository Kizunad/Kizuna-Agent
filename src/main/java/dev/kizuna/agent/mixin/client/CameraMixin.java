package dev.kizuna.agent.mixin.client;

import dev.kizuna.agent.client.capture.ScreenCapture;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 助手要从指定机位截图时，把相机摆到那个机位：只动相机，不动玩家，也不改视角设置。 */
@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Inject(method = "update", at = @At("HEAD"))
    private void kizunaAgent$beginFrame(DeltaTracker deltaTracker, CallbackInfo info) {
        ScreenCapture.beginFrame();
    }

    @Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V",
            shift = At.Shift.AFTER))
    private void kizunaAgent$placeForScreenshot(DeltaTracker deltaTracker, CallbackInfo info) {
        // 相机照常跟着玩家摆好以后再挪走；算作「不在玩家眼睛里」，玩家自己的模型照常画出来。
        // 挂在「摆好」这一步返回之后，而不是它里面的末尾：别的模组（越肩视角之类）可能在里面提前返回。
        ScreenCapture.pose().ifPresent(pose -> {
            setRotation(pose.yaw(), pose.pitch());
            setPosition(pose.x(), pose.y(), pose.z());
            detached = true;
        });
    }

    @Inject(method = "calculateFov", at = @At("HEAD"), cancellable = true)
    private void kizunaAgent$fovForScreenshot(float partialTicks, CallbackInfoReturnable<Float> info) {
        ScreenCapture.pose().ifPresent(pose -> pose.fov().ifPresent(fov -> info.setReturnValue((float) fov)));
    }
}
