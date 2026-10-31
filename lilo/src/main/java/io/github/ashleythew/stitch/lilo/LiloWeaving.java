package io.github.ashleythew.stitch.lilo;

import io.github.ashleythew.stitch.core.Stitch;
import io.github.ashleythew.stitch.core.Weaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Stitches woven into LILO's translations of shader pack programs, by the game's pipeline each draws in place of
 * (armor_cutout_no_cull, entity_translucent, item_cutout ...). LILO keeps those apart where Iris shares one program
 * between them, so a stitch for armour isn't woven into every mob.
 */
public final class LiloWeaving {

    public static final Logger LOG = LoggerFactory.getLogger("stitch");

    /** -Dstitch.dump=true: every program woven into, before and after, into stitch-dump/. */
    private static final boolean DUMP = Boolean.getBoolean("stitch.dump");

    /** The stitches in the enabled resource packs; set by the loader's mod. */
    private static volatile Supplier<List<Stitch>> stitches = List::of;

    private LiloWeaving() {
    }

    public static void stitches(Supplier<List<Stitch>> source) {
        stitches = source;
    }

    private static void dump(String name, String kind, String vertex, String fragment) {
        java.nio.file.Path dir = java.nio.file.Path.of("stitch-dump");
        String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_");
        try {
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve(safe + "." + kind + ".vertex"), vertex);
            java.nio.file.Files.writeString(dir.resolve(safe + "." + kind + ".fragment"), fragment);
        } catch (java.io.IOException e) {
            LOG.warn("couldn't dump {}: {}", name, e.getMessage());
        }
    }

    /** The targets drawn with the game's pipeline. */
    static Set<String> targets(String pipeline, boolean hand) {
        Set<String> out = new LinkedHashSet<>();
        if (pipeline.startsWith("armor")) {
            out.add("armor");
        } else if (pipeline.startsWith("entity") || pipeline.equals("breeze_wind") || pipeline.equals("energy_swirl")) {
            out.add("entity");
        } else if (pipeline.startsWith("item")) {
            out.add("item_world");
        } else if (pipeline.startsWith("text")) {
            out.add("text_world");
        } else if (pipeline.equals("glint")) {
            out.add("glint");
        }
        if (hand && out.remove("entity")) {
            out.add("item_world");
        }
        return out;
    }

    /** The program with the stitches for its targets woven in, as {vertex, fragment}; or null to leave it. */
    public static String[] weave(String program, String pipeline, boolean shadow, boolean hand, String vertex, String fragment) {
        Set<String> targets = targets(pipeline, hand);
        if (targets.isEmpty()) {
            return null;
        }
        List<Stitch> use = new ArrayList<>();
        for (Stitch s : stitches.get()) {
            if (s.targets().stream().anyMatch(targets::contains)) {
                use.add(s);
            }
        }
        if (use.isEmpty()) {
            return null;
        }
        String name = program + "/" + pipeline;
        try {
            Weaver.Woven woven = Weaver.weave(vertex, fragment, LiloShape.of(shadow, vertex, fragment), use);
            for (Map.Entry<String, String> skip : woven.skipped().entrySet()) {
                LOG.warn("{} left out of {}: {}", skip.getKey(), name, skip.getValue());
            }
            if (woven.applied().isEmpty()) {
                return null;
            }
            LOG.info("{}: {}", name, woven.applied());
            if (DUMP) {
                dump(name, "lilo", vertex, fragment);
                dump(name, "stitched", woven.vertex(), woven.fragment());
            }
            return new String[]{woven.vertex(), woven.fragment()};
        } catch (RuntimeException e) {
            LOG.error("couldn't weave into {}, left as it was: {}", name, e.toString());
            return null;
        }
    }
}
