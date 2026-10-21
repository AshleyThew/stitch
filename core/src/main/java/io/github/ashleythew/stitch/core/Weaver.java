package io.github.ashleythew.stitch.core;

import io.github.ashleythew.stitch.core.Glsl.Kind;
import io.github.ashleythew.stitch.core.Glsl.Token;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Weaves stitches into a program's vertex and fragment shaders:
 * <ol>
 * <li>the vertex inputs are read once, at the start of main, into globals the stitches have changed, and
 * every other read of them reads those globals;</li>
 * <li>each albedo sample in the fragment shader passes through the stitches' texel functions;</li>
 * <li>a hidden vertex is moved outside the clip volume.</li>
 * </ol>
 * The stitches' own names are prefixed per stitch so they can't collide.
 */
public final class Weaver {

    /** The result: the two shaders, and the stitches left out with why. */
    public record Woven(String vertex, String fragment, List<String> applied, Map<String, String> skipped) {
    }

    private static final String[][] FIELDS = {
        {"position", "vec3", "vec3(0.0)"}, {"uv", "vec2", "vec2(0.0)"}, {"color", "vec4", "vec4(1.0)"},
        {"normal", "vec3", "vec3(0.0, 1.0, 0.0)"}, {"light", "ivec2", "ivec2(240)"}, {"overlay", "ivec2", "ivec2(0, 10)"},
    };

    private Weaver() {
    }

    /**
     * The stitches for this target that the shape can take, in order, woven into vertex and fragment.
     * Either may be null, for a renderer that compiles one stage before reading the other (OptiFine): the
     * same stitches and shape weave the two halves the same way, so they still fit together.
     */
    public static Woven weave(String vertex, String fragment, ProgramShape shape, List<Stitch> stitches) {
        List<String> applied = new ArrayList<>();
        Map<String, String> skipped = new java.util.LinkedHashMap<>();
        List<Stitch> use = new ArrayList<>();
        for (Stitch s : stitches) {
            List<String> missing = new ArrayList<>();
            for (String r : s.requires()) {
                if (!shape.capabilities.contains(r)) {
                    missing.add(r);
                }
            }
            if (!missing.isEmpty()) {
                skipped.put(s.id(), "this program can't give " + String.join(", ", missing));
            } else if (s.passes().contains(shape.shadow ? "shadow" : "main")) {
                use.add(s);
            }
        }
        use.sort(Comparator.comparingInt(Stitch::order));
        if (use.isEmpty()) {
            return new Woven(vertex, fragment, applied, skipped);
        }
        List<Token> vt = Glsl.tokens(vertex == null ? "" : vertex);
        List<Token> ft = Glsl.tokens(fragment == null ? "" : fragment);
        int location = Math.max(Glsl.highestLocation(vt), Glsl.highestLocation(ft)) + 1;

        StringBuilder vertexTop = new StringBuilder("\n// Stitch: the vertex as the stitches leave it\n");
        vertexTop.append(STRUCTS);
        for (String[] f : FIELDS) {
            vertexTop.append(f[1]).append(" stitch_").append(f[0]).append(";\n");
        }
        vertexTop.append("bool stitch_hidden;\n");

        StringBuilder varyingsOut = new StringBuilder();
        StringBuilder varyingsIn = new StringBuilder();
        StringBuilder vertexCode = new StringBuilder(shape.preamble);
        if (shape.capabilities.contains("texture_size") && !Glsl.declaresUniform(vt, shape.sampler)) {
            vertexCode.append("uniform sampler2D ").append(shape.sampler).append(";\n");
        }
        vertexCode.append(helpers(shape, true));
        StringBuilder fragmentCode = new StringBuilder(STRUCTS);
        if (shape.capabilities.contains("texture_size") && !Glsl.declaresUniform(ft, shape.sampler)) {
            fragmentCode.append("uniform sampler2D ").append(shape.sampler).append(";\n");
        }
        fragmentCode.append(helpers(shape, false));
        StringBuilder run = new StringBuilder("void stitch_run() {\n    StitchVertex v = StitchVertex(");
        for (int i = 0; i < FIELDS.length; i++) {
            String name = shape.inputs.get(FIELDS[i][0]);
            run.append(i > 0 ? ", " : "").append(name != null ? FIELDS[i][1] + "(" + name + ")" : FIELDS[i][2]);
        }
        run.append(", ").append(shape.corner != null ? "stitch_corner_index()" : "0").append(", false);\n");
        StringBuilder texel = new StringBuilder("vec4 stitch_texel_all(vec4 texel, vec2 uv) {\n    StitchFragment f = StitchFragment(uv, ")
            .append(shape.shadow).append(");\n");

        for (Stitch s : use) {
            Map<String, String> names = new HashMap<>();
            String p = "stitch_" + s.prefix() + "_";
            for (Stitch.Varying v : s.varyings()) {
                names.put(v.name(), p + v.name());
                String layout = shape.layouts ? "layout(location = " + location++ + ") " : "";
                String flat = v.flat() ? "flat " : "";
                varyingsOut.append(layout).append(flat).append("out ").append(v.type()).append(' ').append(p).append(v.name()).append(";\n");
                varyingsIn.append(layout).append(flat).append("in ").append(v.type()).append(' ').append(p).append(v.name()).append(";\n");
            }
            if (s.vertex() != null) {
                List<Token> st = Glsl.tokens(s.vertex());
                for (String n : Glsl.declared(st)) {
                    names.putIfAbsent(n, p + n);
                }
                Glsl.rename(st, names);
                vertexCode.append("// ").append(s.id()).append('\n').append(Glsl.join(st)).append('\n');
                run.append("    ").append(p).append("stitch_vertex(v);\n");
            }
            if (s.fragment() != null) {
                List<Token> st = Glsl.tokens(s.fragment());
                Map<String, String> fnames = new HashMap<>(names);
                for (String n : Glsl.declared(st)) {
                    fnames.putIfAbsent(n, p + n);
                }
                Glsl.rename(st, fnames);
                fragmentCode.append("// ").append(s.id()).append('\n').append(Glsl.join(st)).append('\n');
                texel.append("    texel = ").append(p).append("stitch_texel(f, texel);\n");
            }
            applied.add(s.id());
        }
        for (String[] f : FIELDS) {
            run.append("    stitch_").append(f[0]).append(" = v.").append(f[0]).append(";\n");
        }
        run.append("    stitch_hidden = v.hidden;\n}\n");
        texel.append("    return texel;\n}\n");
        vertexCode.append(run);
        fragmentCode.append(texel);

        String wovenVertex = vertex == null ? null : weaveVertex(vt, shape, vertexTop.toString(), varyingsOut + vertexCode.toString());
        String wovenFragment = fragment == null ? null : weaveFragment(ft, shape, varyingsIn + fragmentCode.toString());
        return new Woven(wovenVertex, wovenFragment, applied, skipped);
    }

    private static final String STRUCTS = """
        struct StitchVertex {
            vec3 position;
            vec2 uv;
            vec4 color;
            vec3 normal;
            ivec2 light;
            ivec2 overlay;
            int corner;
            bool hidden;
        };
        struct StitchFragment {
            vec2 uv;
            bool shadow;
        };
        """;

    private static String helpers(ProgramShape shape, boolean vertex) {
        StringBuilder out = new StringBuilder();
        if (shape.capabilities.contains("texture_size")) {
            out.append("ivec2 stitch_texture_size() { return textureSize(").append(shape.sampler).append(", 0); }\n");
            out.append("vec4 stitch_texel_fetch(ivec2 p) { return texelFetch(").append(shape.sampler).append(", p, 0); }\n");
        }
        if (vertex) {
            out.append("bool stitch_in_screen() { return ").append(shape.inScreen).append("; }\n");
        }
        if (vertex && shape.corner != null) {
            out.append("int stitch_corner_index() { return ").append(shape.corner).append("; }\n");
        }
        if (vertex && shape.up != null) {
            out.append("vec3 stitch_up() { return ").append(shape.up).append("; }\n");
            out.append("float stitch_handedness() { return ").append(shape.handedness).append("; }\n");
        }
        if (vertex && shape.gameTime != null) {
            out.append("#define stitch_game_time (").append(shape.gameTime).append(")\n");
        }
        if (vertex && shape.screenSize != null) {
            out.append("#define stitch_screen_size (").append(shape.screenSize).append(")\n");
        }
        return out.toString();
    }

    private static String weaveVertex(List<Token> tokens, ProgramShape shape, String top, String beforeMain) {
        // every read of an input, outside its declaration, reads the stitched global instead
        Map<String, String> reads = new HashMap<>();
        for (Map.Entry<String, String> e : shape.inputs.entrySet()) {
            if (e.getValue().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                reads.put(e.getValue(), "stitch_" + e.getKey());
            }
        }
        List<int[]> declarations = Glsl.inputs(tokens);
        int[] main = Glsl.main(tokens);
        if (main == null) {
            throw new IllegalArgumentException("the vertex shader has no main");
        }
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind() != Kind.IDENT || !reads.containsKey(t.text())) {
                continue;
            }
            boolean declaring = false;
            for (int[] d : declarations) {
                if (i >= d[0] && i <= d[1]) {
                    declaring = true;
                    break;
                }
            }
            // inside stitch_run (added below) the inputs are read as they are; elsewhere, stitched
            if (!declaring) {
                tokens.set(i, new Token(Kind.IDENT, reads.get(t.text())));
            }
        }
        // the end of main first (its indexes come after the others)
        tokens.add(main[1], new Token(Kind.SPACE, "    if (stitch_hidden) {\n        gl_Position = vec4(-2.0, -2.0, -2.0, 1.0);\n    }\n"));
        tokens.add(main[0] + 1, new Token(Kind.SPACE, "\n    stitch_run();"));
        int mainStatement = statementStart(tokens, main[0]);
        tokens.add(mainStatement, new Token(Kind.SPACE, beforeMain + "\n"));
        tokens.add(topInsertion(tokens), new Token(Kind.SPACE, top));
        String out = Glsl.join(tokens);
        if (shape.vertexHeader != null) {
            out = out.replaceFirst("(?m)^#version[^\\n]*", java.util.regex.Matcher.quoteReplacement(shape.vertexHeader.strip()));
        }
        return out;
    }

    private static String weaveFragment(List<Token> tokens, ProgramShape shape, String beforeMain) {
        List<Glsl.Call> calls = Glsl.calls(tokens, shape.textureFunctions, shape.sampler);
        // from the last, so earlier indexes stay right
        for (int c = calls.size() - 1; c >= 0; c--) {
            Glsl.Call call = calls.get(c);
            String uv = Glsl.join(new ArrayList<>(tokens.subList(call.comma() + 1, uvEnd(tokens, call))));
            tokens.add(call.close() + 1, new Token(Kind.SPACE, ", vec2(" + uv.strip() + "))"));
            tokens.add(call.name(), new Token(Kind.SPACE, "stitch_texel_all("));
        }
        int[] main = Glsl.main(tokens);
        if (main == null) {
            throw new IllegalArgumentException("the fragment shader has no main");
        }
        tokens.add(statementStart(tokens, main[0]), new Token(Kind.SPACE, beforeMain + "\n"));
        return Glsl.join(tokens);
    }

    /** The end of the call's uv argument: its next top-level comma (a bias or lod) or its ')'. */
    private static int uvEnd(List<Token> tokens, Glsl.Call call) {
        int depth = 0;
        for (int j = call.comma() + 1; j < call.close(); j++) {
            Token t = tokens.get(j);
            if (t.is("(") || t.is("[")) {
                depth++;
            } else if (t.is(")") || t.is("]")) {
                depth--;
            } else if (depth == 0 && t.is(",")) {
                return j;
            }
        }
        return call.close();
    }

    /** Where the top-level statement holding this index starts: after the previous ';' or '}' at depth 0. */
    private static int statementStart(List<Token> tokens, int index) {
        for (Glsl.Statement s : Glsl.statements(tokens)) {
            if (s.start() <= index && (s.bodyClose() >= index || s.end() >= index)) {
                return s.start();
            }
        }
        return index;
    }

    /** Just after the #version and #extension lines at the top, where declarations may start. */
    private static int topInsertion(List<Token> tokens) {
        int at = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind() == Kind.DIRECTIVE) {
                String d = t.text().substring(1).trim();
                if (d.startsWith("version") || d.startsWith("extension")) {
                    at = i + 1;
                }
                continue;
            }
            if (!t.blank()) {
                break;
            }
        }
        return at;
    }

    /** Stitches for a target from several packs, in pack order (lowest first) then each pack's order. */
    public static List<Stitch> forTarget(List<Stitch> stitches, String target) {
        List<Stitch> out = new ArrayList<>();
        for (Stitch s : stitches) {
            if (s.targets().contains(target)) {
                out.add(s);
            }
        }
        return out;
    }

    /** The capabilities a set of stitches needs, for readers deciding whether to bother. */
    public static Set<String> required(List<Stitch> stitches) {
        Set<String> out = new java.util.TreeSet<>();
        for (Stitch s : stitches) {
            out.addAll(s.requires());
        }
        return out;
    }
}
