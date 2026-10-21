package io.github.ashleythew.stitch.optifine;

import io.github.ashleythew.stitch.core.Preprocessor;
import io.github.ashleythew.stitch.core.ProgramShape;
import io.github.ashleythew.stitch.core.Stitch;
import io.github.ashleythew.stitch.core.Weaver;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Stitches woven into OptiFine's shader pack programs, by the program's name (gbuffers_entities,
 * gbuffers_hand, shadow...). OptiFine compiles a program's vertex shader before it reads the fragment
 * shader, so each stage is woven on its own. Its text reaches Stitch unpreprocessed (a shader pack often
 * writes every stage in one file, behind #ifdef VERTEX_SHADER), so its conditionals are resolved first.
 */
public final class OptiFineWeaving {

    public static final Logger LOG = LoggerFactory.getLogger("stitch");

    /** -Dstitch.dump=true: every program OptiFine finishes, before and after weaving, into stitch-dump/. */
    private static final boolean DUMP = Boolean.getBoolean("stitch.dump");

    /** The stitches in the enabled resource packs; set by the loader's mod. */
    private static volatile Supplier<List<Stitch>> stitches = List::of;

    /** The driver's GL extensions, which GLSL defines as macros; read once, on the render thread. */
    private static volatile Set<String> extensions;

    /** The program and stage OptiFine is compiling on this thread. */
    private static final ThreadLocal<String[]> COMPILING = new ThreadLocal<>();

    private OptiFineWeaving() {
    }

    public static void stitches(Supplier<List<Stitch>> source) {
        stitches = source;
    }

    /** OptiFine has started compiling a stage (vertex, fragment, geometry) of the named program. */
    public static void compiling(String program, String stage) {
        COMPILING.set(program == null ? null : new String[]{program, stage});
    }

    public static void compiled() {
        COMPILING.remove();
    }

    /** The targets drawn with a program, by its name. */
    static Set<String> targets(String program) {
        Set<String> out = new LinkedHashSet<>();
        if (program.startsWith("gbuffers_entities") || program.equals("shadow") || program.startsWith("shadow_")) {
            // armour and every other entity model share the entity programs, and every shadow is drawn by one
            out.add("armor");
            out.add("entity");
        } else if (program.startsWith("gbuffers_hand")) {
            out.add("item_world");
        } else if (program.equals("gbuffers_armor_glint")) {
            out.add("glint");
        }
        return out;
    }

    /** The source OptiFine is about to compile, with the stitches for its program woven in. */
    public static String weave(String source) {
        String[] compiling = COMPILING.get();
        if (compiling == null || source == null || compiling[1].equals("geometry")) {
            return source;
        }
        String program = compiling[0];
        boolean vertex = compiling[1].equals("vertex");
        Set<String> targets = targets(program);
        if (targets.isEmpty()) {
            return source;
        }
        List<Stitch> use = new ArrayList<>();
        for (Stitch s : stitches.get()) {
            if (s.targets().stream().anyMatch(targets::contains)) {
                use.add(s);
            }
        }
        if (use.isEmpty()) {
            return source;
        }
        if (DUMP) {
            dump(program, "optifine", compiling[1], source);
        }
        try {
            String resolved = Preprocessor.resolve(source, extensions());
            ProgramShape shape = OptiFineShape.of(program, resolved, vertex);
            Weaver.Woven woven = vertex ? Weaver.weave(resolved, null, shape, use) : Weaver.weave(null, resolved, shape, use);
            if (vertex) {
                for (Map.Entry<String, String> skip : woven.skipped().entrySet()) {
                    LOG.warn("{} left out of {}: {}", skip.getKey(), program, skip.getValue());
                }
            }
            if (woven.applied().isEmpty()) {
                return source;
            }
            String out = vertex ? woven.vertex() : woven.fragment();
            if (DUMP) {
                dump(program, "stitched", compiling[1], out);
            }
            if (vertex) {
                LOG.info("{}: {}", program, woven.applied());
            }
            return out;
        } catch (RuntimeException e) {
            LOG.error("couldn't weave into {} ({}), left as it was: {}", program, compiling[1], e.toString());
            return source;
        }
    }

    private static Set<String> extensions() {
        Set<String> out = extensions;
        if (out == null) {
            out = new HashSet<>();
            int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            for (int i = 0; i < count; i++) {
                String name = GL30.glGetStringi(GL11.GL_EXTENSIONS, i);
                if (name != null) {
                    out.add(name);
                }
            }
            extensions = out;
        }
        return out;
    }

    private static void dump(String program, String kind, String stage, String source) {
        Path dir = Path.of("stitch-dump");
        try {
            Files.createDirectories(dir);
            String safe = program.replaceAll("[^A-Za-z0-9_.-]", "_");
            Files.writeString(dir.resolve(safe + "." + kind + "." + stage), source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("couldn't dump {}: {}", program, e.getMessage());
        }
    }
}
