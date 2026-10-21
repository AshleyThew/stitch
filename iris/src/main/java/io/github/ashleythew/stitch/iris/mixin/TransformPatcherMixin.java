package io.github.ashleythew.stitch.iris.mixin;

import io.github.ashleythew.stitch.iris.IrisWeaving;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Where Iris (1.7 on) has finished a shader pack program that replaces one of Minecraft's (entities, the
 * hand, text...): patchVanilla's first argument is the program's name, and it returns the program's
 * text by stage. Its other arguments change between Iris releases, so neither handler names them.
 */
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", remap = false)
abstract class TransformPatcherMixin {

    @ModifyVariable(method = "patchVanilla", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static String stitch$name(String name) {
        IrisWeaving.building(name);
        return name;
    }

    @Inject(method = "patchVanilla", at = @At("RETURN"), cancellable = true)
    private static void stitch$weave(CallbackInfoReturnable<Map<Object, String>> cir) {
        Map<Object, String> woven = IrisWeaving.weave(null, cir.getReturnValue());
        if (woven != null) {
            cir.setReturnValue(woven);
        }
    }
}
