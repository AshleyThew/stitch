package io.github.ashleythew.stitch.forge;

import io.github.ashleythew.stitch.minecraft.Stitches;
import io.github.ashleythew.stitch.optifine.OptiFineShape;
import io.github.ashleythew.stitch.optifine.OptiFineWeaving;
import net.minecraftforge.fml.common.Mod;

/**
 * Stitch on Forge: weaves resource packs' stitches into OptiFine's shader pack programs. Only Forge's
 * mod annotation is used, the one part of Forge's API unchanged from 1.18 to today.
 */
@Mod("stitch")
public final class StitchMod {

    public StitchMod() {
        OptiFineShape.viewSpace(Build.VIEW_SPACE);
        OptiFineWeaving.stitches(Stitches::read);
        boolean optifine;
        try {
            Class.forName("net.optifine.Config", false, StitchMod.class.getClassLoader());
            optifine = true;
        } catch (ClassNotFoundException e) {
            optifine = false;
        }
        OptiFineWeaving.LOG.info("Stitch {}: {}", Build.VERSION,
            optifine ? "weaving stitches into OptiFine's shader pack programs" : "OptiFine isn't installed, so there is nothing to weave into");
    }
}
