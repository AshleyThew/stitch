package io.github.ashleythew.stitch.iris.mixin;

import io.github.ashleythew.stitch.iris.IrisWeaving;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Iris 1.6 (to Minecraft 1.19.4) builds each program in NewShaderTests.create, whose first text argument
 * is the program's name; its patchVanilla, below it, isn't told the name.
 */
@Mixin(targets = "net.coderbot.iris.pipeline.newshader.NewShaderTests", remap = false)
abstract class LegacyNewShaderTestsMixin {

    @ModifyVariable(method = "create", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static String stitch$name(String name) {
        IrisWeaving.building(name);
        return name;
    }
}
