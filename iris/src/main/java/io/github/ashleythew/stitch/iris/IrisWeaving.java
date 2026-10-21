package io.github.ashleythew.stitch.iris;

import io.github.ashleythew.stitch.core.Stitch;
import io.github.ashleythew.stitch.core.Weaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Stitches woven into Iris's shader pack programs, by the program's name (Iris's ShaderKey, lower case:
 * entities_cutout, shadow_entities_cutout, hand_cutout...). Iris hands over each program it has
 * finished as a map from its stage enum (VERTEX, FRAGMENT...) to text; the enum's class moved between
 * Iris releases, so stages are found by name and nothing here compiles against Iris.
 */
public final class IrisWeaving {

    public static final Logger LOG = LoggerFactory.getLogger("stitch");

    /** -Dstitch.dump=true: every program Iris finishes, before and after weaving, into stitch-dump/. */
    private static final boolean DUMP = Boolean.getBoolean("stitch.dump");

    /** The stitches in the enabled resource packs; set by the loader's mod. */
    private static volatile Supplier<List<Stitch>> stitches = List::of;

    /** The name of the program Iris is building on this thread, for Iris releases that don't pass it on. */
    private static final ThreadLocal<String> BUILDING = new ThreadLocal<>();

    private IrisWeaving() {
    }

    public static void stitches(Supplier<List<Stitch>> source) {
        stitches = source;
    }

    /** Iris has started building the named program. */
    public static void building(String name) {
        BUILDING.set(name);
    }

    /** The targets drawn with a program, by its name. */
    static Set<String> targets(String name) {
        String n = name.startsWith("shadow_") ? name.substring("shadow_".length()) : name;
        Set<String> out = new LinkedHashSet<>();
        if (n.startsWith("entities")) {
            // armour and every other entity model share Iris's entity programs
            out.add("armor");
            out.add("entity");
        } else if (n.startsWith("hand_") && !n.startsWith("hand_text")) {
            out.add("item_world");
        } else if (n.startsWith("text") || n.startsWith("hand_text")) {
            out.add("text_world");
        } else if (n.equals("glint")) {
            out.add("glint");
        }
        return out;
    }

    /**
     * The program Iris has finished, with the stitches for its targets woven in; or null to leave it as
     * Iris made it. The name is the program's, or null for the one {@link #building} last named.
     */
    public static <K> Map<K, String> weave(String name, Map<K, String> program) {
        if (name == null) {
            name = BUILDING.get();
            BUILDING.remove();
        }
        if (program == null || name == null) {
            return null;
        }
        K vertexKey = stage(program, "VERTEX");
        K fragmentKey = stage(program, "FRAGMENT");
        Set<String> targets = targets(name);
        if (vertexKey == null || fragmentKey == null || targets.isEmpty()) {
            return null;
        }
        String vertex = program.get(vertexKey);
        String fragment = program.get(fragmentKey);
        List<Stitch> use = new ArrayList<>();
        for (Stitch s : stitches.get()) {
            if (s.targets().stream().anyMatch(targets::contains)) {
                use.add(s);
            }
        }
        if (use.isEmpty()) {
            return null;
        }
        if (DUMP) {
            dump(name, "iris", program);
        }
        try {
            Weaver.Woven woven = Weaver.weave(vertex, fragment, IrisShape.of(name, vertex, fragment), use);
            for (Map.Entry<String, String> skip : woven.skipped().entrySet()) {
                LOG.warn("{} left out of {}: {}", skip.getKey(), name, skip.getValue());
            }
            if (woven.applied().isEmpty()) {
                return null;
            }
            Map<K, String> out = new LinkedHashMap<>(program);
            out.put(vertexKey, woven.vertex());
            out.put(fragmentKey, woven.fragment());
            if (DUMP) {
                dump(name, "stitched", out);
            }
            LOG.info("{}: {}", name, woven.applied());
            return out;
        } catch (RuntimeException e) {
            LOG.error("couldn't weave into {}, left as it was: {}", name, e.toString());
            return null;
        }
    }

    /** The key of the stage with this name (VERTEX, FRAGMENT...) and text, or null. */
    private static <K> K stage(Map<K, String> program, String stage) {
        for (Map.Entry<K, String> e : program.entrySet()) {
            if (e.getValue() != null && e.getKey() instanceof Enum<?> key && key.name().equals(stage)) {
                return e.getKey();
            }
        }
        return null;
    }

    private static void dump(String name, String kind, Map<?, String> program) {
        Path dir = Path.of("stitch-dump");
        try {
            Files.createDirectories(dir);
            for (Map.Entry<?, String> e : program.entrySet()) {
                if (e.getValue() != null) {
                    String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_");
                    Files.writeString(dir.resolve(safe + "." + kind + "." + e.getKey().toString().toLowerCase()), e.getValue(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            LOG.warn("couldn't dump {}: {}", name, e.getMessage());
        }
    }
}
