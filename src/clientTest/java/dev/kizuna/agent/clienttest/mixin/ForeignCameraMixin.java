package dev.kizuna.agent.clienttest.mixin;

import dev.kizuna.agent.clienttest.ForeignCamera;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 扮演「另一个也改相机的模组」：开关打开时，在相机跟着玩家摆好位置之后就提前返回，后面的原版步骤不走。
 * 越肩视角一类的模组就是这么接管相机的；助手指定机位的截图要在这种情况下照样截得对。
 */
@Mixin(Camera.class)
abstract class ForeignCameraMixin {
    @Inject(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V",
            shift = At.Shift.AFTER), cancellable = true)
    private void kizunaAgentTest$takeOver(CallbackInfo callback) {
        if (ForeignCamera.active) {
            callback.cancel();
        }
    }
}
