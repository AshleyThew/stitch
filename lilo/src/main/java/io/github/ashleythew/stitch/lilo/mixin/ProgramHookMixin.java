package io.github.ashleythew.stitch.lilo.mixin;

import io.github.ashleythew.stitch.lilo.LiloWeaving;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Where LILO has translated a shader pack's program: it hands over the text and takes back what's returned. */
@Mixin(targets = "io.github.ashleythew.lilo.api.ProgramHook", remap = false)
abstract class ProgramHookMixin {

    @Inject(method = "finished", at = @At("RETURN"), cancellable = true)
    private static void stitch$weave(String program, String pipeline, boolean shadow, boolean hand, String vertex, String fragment,
                                     CallbackInfoReturnable<String[]> cir) {
        String[] woven = LiloWeaving.weave(program, pipeline, shadow, hand, vertex, fragment);
        if (woven != null) {
            cir.setReturnValue(woven);
        }
    }
}
