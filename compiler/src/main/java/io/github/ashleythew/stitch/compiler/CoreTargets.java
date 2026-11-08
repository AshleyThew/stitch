package io.github.ashleythew.stitch.compiler;

import io.github.ashleythew.stitch.core.Glsl;
import io.github.ashleythew.stitch.core.ProgramShape;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which of Minecraft's core shaders draw each target, and how to weave into them, by resource pack format
 * (docs/SPEC.md, Targets). Minecraft's version differences live here, once.
 */
final class CoreTargets {

    /** The core shaders that draw each target: per release, the first of these it has. */
    static final Map<String, List<String>> SHADERS = Map.of(
        // to 1.21.1 armour has its own shader; from 1.21.2 it's the entity shader every entity draws with
        "armor", List.of("rendertype_armor_cutout_no_cull", "entity"),
        // text in the world (and maps in item frames): rendertype_text, renamed text in 26.2
        "text_world", List.of("text", "rendertype_text"));

    /**
     * Where a target's shader is shared with drawing outside it, the defines that tell its own programs apart
     * (Minecraft's program JSONs to 1.21.4, its render pipelines after): woven in only there, so the rest
     * compile no faster or slower than without the stitches. The entity shader is armour's from 1.21.2, and
     * armour's programs are the cut-out ones without an overlay that neither move their texture (breeze wind,
     * energy swirl) nor glow (eyes).
     */
    static final Map<String, String> SHARED = Map.of(
        "armor:entity", "defined(ALPHA_CUTOUT) && defined(NO_OVERLAY) && !defined(APPLY_TEXTURE_MATRIX) && !defined(EMISSIVE)");
    /**
     * From 26.1 (format 84) the end crystal's beam is drawn with the entity shader too, cut out without an
     * overlay as armour is; armour alone of the two lights each face (PER_FACE_LIGHTING, which armour has had
     * since 1.21.10).
     */
    static final int END_CRYSTAL_BEAM_FROM = 84;
    static final String PER_FACE = " && defined(PER_FACE_LIGHTING)";

    /** Before 1.20.5 (format 32) entities are drawn in view space, IViewRotMat turning that back. */
    static final int MODEL_SPACE_FROM = 32;
    /**
     * From 26.3 (format 97, the renderpearl renderer) draws start at a base vertex part way into a shared
     * buffer, so a quad's corner is counted from the draw's start: GLSL 450 with draw parameters, where
     * the device has them (RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE); elsewhere no corner (-1).
     */
    static final int DRAW_PARAMETERS_FROM = 97;
    /** From 1.21.2 (format 42) a core shader has no program JSON; its uniforms are bound by name. */
    static final int NO_PROGRAM_JSON_FROM = 42;
    /** From 1.21.6 (format 63) the game time is in the Globals block every program can import. */
    static final int GLOBALS_FROM = 63;

    static final String VIEW_ROTATION = "IViewRotMat";

    /**
     * From 26.2 (format 88) depth runs the other way: far at -1 (0 in Vulkan's range) and near at 1. And the
     * text pipelines bind the Globals block (the clock and the screen's size) from then; 1.21.2 to 26.1 give
     * text shaders neither, and a Vulkan pipeline rejects a shader using a block it doesn't bind.
     */
    static final int REVERSED_DEPTH_FROM = 88;
    static final int TEXT_GLOBALS_FROM = 88;
    /** Where the text shaders have no screen size, the screen taken as 1080 high, as wide as the projection's shape. */
    static final String SCREEN_GUESS = "vec2(1080.0 * ProjMat[1][1] / ProjMat[0][0], 1080.0)";

    private CoreTargets() {
    }

    static boolean supported(String target) {
        return SHADERS.containsKey(target);
    }

    /** Where the target's own programs are, in the release's shader of this name: a condition, or null for all of it. */
    static String guard(String target, String shader, int format) {
        String guard = SHARED.get(target + ":" + shader);
        if (guard != null && target.equals("armor") && format >= END_CRYSTAL_BEAM_FROM) {
            guard += PER_FACE;
        }
        return guard;
    }

    /** How the release's shader of this name, for the target, is woven into. */
    static ProgramShape shape(String target, String shader, int format, String vertex, String fragment) {
        ProgramShape shape = new ProgramShape();
        // the inputs the shader has (text has neither a normal nor an overlay)
        String[][] inputs = {{"position", "Position"}, {"uv", "UV0"}, {"color", "Color"}, {"normal", "Normal"},
            {"light", "UV2"}, {"overlay", "UV1"}};
        for (String[] in : inputs) {
            if (declaredAlways(vertex, in[1])) {
                shape.input(in[0], in[1]);
            }
        }
        shape.albedo("Sampler0", Set.of("texture"))
            .inScreen("(ProjMat[2][3] == 0.0)")
            .layouts(Glsl.highestLocation(Glsl.tokens(vertex)) >= 0)
            .guard(guard(target, shader, format));
        StringBuilder preamble = new StringBuilder();
        if (format >= DRAW_PARAMETERS_FROM) {
            shape.vertexHeader("#version 450\n#extension GL_ARB_shader_draw_parameters : enable\n#define STITCH_DRAW_PARAMETERS");
        }
        // A vertex's number from its draw's start. OpenGL's gl_VertexID counts from the buffer's, the base
        // vertex included: 26.2 draws entities from a shared buffer at a base vertex that needn't be a
        // multiple of 4, which puts every corner in the wrong place. Taken off where the driver has draw
        // parameters; 0 for a draw from the buffer's start, so nothing changes for the releases before.
        preamble.append("""
            #if defined(VULKAN) && defined(STITCH_DRAW_PARAMETERS) && defined(RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE)
            #define STITCH_VERTEX_ID (gl_VertexIndex - gl_BaseVertexARB)
            #elif defined(VULKAN)
            #define STITCH_VERTEX_ID (-4)
            #elif defined(GL_ARB_shader_draw_parameters) && (defined(STITCH_ARB_DRAW_PARAMETERS) || defined(STITCH_DRAW_PARAMETERS))
            #define STITCH_VERTEX_ID (gl_VertexID - gl_BaseVertexARB)
            #else
            #define STITCH_VERTEX_ID gl_VertexID
            #endif
            """);
        shape.corner("(STITCH_VERTEX_ID < 0 ? -1 : STITCH_VERTEX_ID % 4)");
        shape.vertexIndex("(STITCH_VERTEX_ID < 0 ? -1 : STITCH_VERTEX_ID)");
        if (format < DRAW_PARAMETERS_FROM) {
            // a quad's corners shared by subgroup shuffles, where the driver has them (26.3's Vulkan GLSL
            // is left out: there they would need a newer SPIR-V than Minecraft asks for)
            // (the base vertex is read only where its extension is enabled, here or by 26.3's header: a driver
            // can define GL_ARB_shader_draw_parameters in a shader that never enabled it, which can't use it)
            shape.quadCorners("Position", "UV0", "STITCH_VERTEX_ID",
                "#extension GL_ARB_shader_draw_parameters : enable\n#define STITCH_ARB_DRAW_PARAMETERS\n"
                    + "#extension GL_KHR_shader_subgroup_basic : enable\n#extension GL_KHR_shader_subgroup_shuffle : enable");
        }
        // What a stitch draws can sit where it says, at its depth. A point's window depth is found the
        // way this fragment's own was: OpenGL maps -1 to 1 onto 0 to 1, a Vulkan-style renderer (26.3)
        // takes 0 to 1 as it is, and the fragment's own point tells which.
        String depth = "vec4 c = ProjMat * ModelViewMat * vec4(p, 1.0);\n"
            + "    vec4 h = ProjMat * ModelViewMat * vec4(stitch_fragment_position, 1.0);\n"
            + "    float zh = h.z / h.w;\n"
            + "    float z = c.z / c.w;\n"
            + "    return abs(gl_FragCoord.z - (zh * 0.5 + 0.5)) < abs(gl_FragCoord.z - zh) ? z * 0.5 + 0.5 : z;";
        String matrices;
        if (format < GLOBALS_FROM) {
            // uniforms bound by name (and listed in the program JSON, to 1.21.1)
            matrices = "uniform mat4 ModelViewMat;\nuniform mat4 ProjMat;";
        } else {
            // from 1.21.6 they are uniform blocks, imported
            String include = vertex.contains("#include") ? "#include" : "#moj_import";
            matrices = include + " <minecraft:dynamictransforms.glsl>\n" + include + " <minecraft:projection.glsl>";
        }
        shape.fragmentPosition().fragmentDepth(depth, matrices);
        String worldUp = "vec3(0.0, 1.0, 0.0)";
        if (format < MODEL_SPACE_FROM) {
            worldUp = "(transpose(" + VIEW_ROTATION + ") * vec3(0.0, 1.0, 0.0))";
            if (!Glsl.declaresUniform(Glsl.tokens(vertex), VIEW_ROTATION)) {
                preamble.append("uniform mat3 ").append(VIEW_ROTATION).append(";\n");
            }
        } else {
            shape.modelSpace();
        }
        // in a screen's entity preview (an orthographic projection) up is the screen's, found from the matrices
        shape.orientation(
            "(ProjMat[2][3] == 0.0 ? normalize(inverse(mat3(ModelViewMat)) * vec3(0.0, sign(ProjMat[1][1]), 0.0)) : " + worldUp + ")",
            "(ProjMat[2][3] == 0.0 ? sign(determinant(mat3(ModelViewMat))) * sign(ProjMat[0][0]) * sign(ProjMat[1][1]) : 1.0)");
        shape.preamble(preamble.toString());
        // a point on the screen just in front of everything, each layer a little nearer
        shape.screenPosition(format >= REVERSED_DEPTH_FROM ? "return vec4(p, 0.9999 + 0.00002 * layer, 1.0);"
            : "return vec4(p, -0.9999 - 0.00002 * layer, 1.0);");
        // the camera's turn: IViewRotMat undoes it before 1.20.5, where it is in the vertices
        shape.viewRotation(format < MODEL_SPACE_FROM ? "transpose(" + VIEW_ROTATION + ")" : "mat3(ModelViewMat)");
        // the colour as it is, past the shader's lighting and fog; 26.3 draws some text in two passes, and its
        // alpha-only one writes no colour
        if (fragment != null && fragment.contains("fragColor")) {
            shape.finalColor(fragment.contains("OIT_ALPHA_ONLY")
                ? "#ifdef OIT_ALPHA_ONLY\n            executeAlphaOnlyPhase(gl_FragCoord.z, $C.a);\n#else\n            fragColor = $C;\n#endif"
                : "fragColor = $C;");
        }
        String include = vertex.contains("#include") ? "#include" : "#moj_import";
        if (target.equals("text_world")) {
            if (format < NO_PROGRAM_JSON_FROM) {
                // listed in the program JSON (see programJson)
                shape.uniform("game_time", "GameTime", "GameTime", "uniform float GameTime;");
                shape.uniform("screen_size", "ScreenSize", "ScreenSize", "uniform vec2 ScreenSize;");
            } else if (format >= TEXT_GLOBALS_FROM) {
                shape.uniform("game_time", "GameTime", "GameTime", include + " <minecraft:globals.glsl>");
                shape.uniform("screen_size", "ScreenSize", "ScreenSize", include + " <minecraft:globals.glsl>");
            } else {
                // no clock here, so a stitch's stands still; the screen's size from the projection
                shape.uniform("screen_size", SCREEN_GUESS, "ProjMat", "");
            }
            return shape;
        }
        if (format >= GLOBALS_FROM) {
            shape.uniform("game_time", "GameTime", "GameTime", include + " <minecraft:globals.glsl>");
        } else {
            // to 1.21.1 the program JSON lists it too (see GAME_TIME_UNIFORM)
            shape.uniform("game_time", "GameTime", "GameTime", "uniform float GameTime;");
        }
        return shape;
    }

    /** The uniform the program JSON (to 1.21.1) must list for Minecraft to set the game time. */
    static final Map<String, Object> GAME_TIME_UNIFORM = Map.of("name", "GameTime", "type", "float", "count", 1.0,
        "values", List.of(0.0));

    /**
     * Whether the vertex shader declares this input outside every #if: from 26.2 the text shader declares its light
     * (UV2) only for the programs that aren't drawn in a screen or through walls, so the others have none to read.
     * One declared only sometimes is left to the shader, a stitch's change to it going nowhere.
     */
    static boolean declaredAlways(String vertex, String input) {
        java.util.regex.Pattern declaration = java.util.regex.Pattern.compile("\\bin\\s+\\w+\\s+" + input + "\\s*;");
        int depth = 0;
        boolean found = false;
        for (String line : vertex.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#if")) {
                depth++;
            } else if (t.startsWith("#endif")) {
                depth--;
            } else if (declaration.matcher(t).find()) {
                if (depth > 0) {
                    return false;
                }
                found = true;
            }
        }
        return found;
    }

    /** The screen's size, which the program JSON (to 1.21.1) must list for the text shader's stitches. */
    static final Map<String, Object> SCREEN_SIZE_UNIFORM = Map.of("name", "ScreenSize", "type", "float", "count", 2.0,
        "values", List.of(1920.0, 1080.0));

    /** The uniform the program JSON (to 1.21.1) must list for what the weaving added. */
    static final Map<String, Object> VIEW_ROTATION_UNIFORM = Map.of("name", VIEW_ROTATION, "type", "matrix3x3", "count", 9.0,
        "values", List.of(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0));
}
