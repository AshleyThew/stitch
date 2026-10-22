package io.github.ashleythew.stitch.forge;

import io.github.ashleythew.stitch.minecraft.Readers;
import io.github.ashleythew.stitch.optifine.OptiFineShape;
import io.github.ashleythew.stitch.optifine.OptiFineWeaving;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Stitch on Forge: weaves resource packs' stitches into OptiFine's shader pack programs. Only Forge's
 * mod annotation is used, the one part of Forge's API unchanged from 1.18 to today.
 */
@Mod("stitch")
public final class StitchMod {

    public StitchMod() {
        boolean official = official();
        // Forge moved to Minecraft's own names at 1.20.6, past 1.20.5, when entity vertices left view space
        OptiFineShape.viewSpace(!official);
        OptiFineWeaving.stitches(Readers.of(official ? "official" : listsToMap() ? "srg119" : "srg118"));
        OptiFineWeaving.LOG.info("Stitch {}: {}", Build.VERSION,
            present("net.optifine.Config") ? "weaving stitches into OptiFine's shader pack programs" : "OptiFine isn't installed, so there is nothing to weave into");
    }

    /**
     * Minecraft runs under its own names (Forge from 1.20.6) rather than SRG's. Read from the running game
     * itself, as Forge's own version APIs have moved over the years.
     */
    private static boolean official() {
        return Arrays.stream(load("net.minecraft.client.Minecraft").getDeclaredMethods()).anyMatch(m -> m.getName().equals("getInstance"));
    }

    /** The resource manager lists files to a Map (from 1.19) rather than a Collection of ids (1.18). */
    private static boolean listsToMap() {
        for (Method method : load("net.minecraft.server.packs.resources.ResourceManager").getMethods()) {
            if (Arrays.equals(method.getParameterTypes(), new Class<?>[] {String.class, Predicate.class}) && Map.class.isAssignableFrom(method.getReturnType())) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, StitchMod.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean present(String name) {
        try {
            Class.forName(name, false, StitchMod.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
