package io.github.ashleythew.stitch.optifine.mixin;

import io.github.ashleythew.stitch.optifine.OptiFinePrograms;
import io.github.ashleythew.stitch.optifine.OptiFineWeaving;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * OptiFine's shader loader: createVertShader, createFragShader and createGeomShader each build one
 * stage of a program and hand its final text to shaderSource, which Stitch weaves its stitches into.
 * The same on every Minecraft version OptiFine supports, and none of it is Minecraft's, so nothing here
 * is remapped.
 */
@Mixin(targets = "net.optifine.shaders.Shaders", remap = false)
abstract class ShadersMixin {

    @Inject(method = "createVertShader", at = @At("HEAD"))
    private static void stitch$vertex(@Coerce Object program, String file, CallbackInfoReturnable<Integer> cir) {
        OptiFineWeaving.compiling(OptiFinePrograms.name(program), "vertex");
    }

    @Inject(method = "createFragShader", at = @At("HEAD"))
    private static void stitch$fragment(@Coerce Object program, String file, CallbackInfoReturnable<Integer> cir) {
        OptiFineWeaving.compiling(OptiFinePrograms.name(program), "fragment");
    }

    @Inject(method = "createGeomShader", at = @At("HEAD"))
    private static void stitch$geometry(@Coerce Object program, String file, CallbackInfoReturnable<Integer> cir) {
        OptiFineWeaving.compiling(OptiFinePrograms.name(program), "geometry");
    }

    @Inject(method = {"createVertShader", "createFragShader", "createGeomShader"}, at = @At("RETURN"))
    private static void stitch$compiled(@Coerce Object program, String file, CallbackInfoReturnable<Integer> cir) {
        OptiFineWeaving.compiled();
    }

    @ModifyVariable(method = "shaderSource", at = @At("HEAD"), argsOnly = true)
    private static String stitch$weave(String source) {
        return OptiFineWeaving.weave(source);
    }
}
