package io.github.ashleythew.stitch.iris.mixin;

import io.github.ashleythew.stitch.iris.IrisWeaving;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** Where Iris 1.6 has finished a program, named by NewShaderTests.create just before. */
@Mixin(targets = "net.coderbot.iris.pipeline.transform.TransformPatcher", remap = false)
abstract class LegacyTransformPatcherMixin {

    @Inject(method = "patchVanilla", at = @At("RETURN"), cancellable = true)
    private static void stitch$weave(CallbackInfoReturnable<Map<Object, String>> cir) {
        Map<Object, String> woven = IrisWeaving.weave(null, cir.getReturnValue());
        if (woven != null) {
            cir.setReturnValue(woven);
        }
    }
}
