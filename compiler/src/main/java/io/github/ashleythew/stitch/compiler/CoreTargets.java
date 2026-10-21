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
        "armor", List.of("rendertype_armor_cutout_no_cull", "entity"));

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

    private CoreTargets() {
    }

    static boolean supported(String target) {
        return SHADERS.containsKey(target);
    }

    /** How the release's shader for the target is woven into. */
    static ProgramShape shape(int format, String vertex) {
        ProgramShape shape = new ProgramShape()
            .input("position", "Position").input("uv", "UV0").input("color", "Color").input("normal", "Normal")
            .input("light", "UV2").input("overlay", "UV1")
            .albedo("Sampler0", Set.of("texture"))
            .inScreen("(ProjMat[2][3] == 0.0)")
            .layouts(Glsl.highestLocation(Glsl.tokens(vertex)) >= 0);
        StringBuilder preamble = new StringBuilder();
        if (format >= DRAW_PARAMETERS_FROM) {
            shape.vertexHeader("#version 450\n#extension GL_ARB_shader_draw_parameters : enable\n#define STITCH_DRAW_PARAMETERS");
        }
        preamble.append("""
            #if defined(VULKAN) && defined(STITCH_DRAW_PARAMETERS) && defined(RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE)
            #define STITCH_VERTEX_ID (gl_VertexIndex - gl_BaseVertexARB)
            #elif defined(VULKAN)
            #define STITCH_VERTEX_ID (-4)
            #else
            #define STITCH_VERTEX_ID gl_VertexID
            #endif
            """);
        shape.corner("(STITCH_VERTEX_ID < 0 ? -1 : STITCH_VERTEX_ID % 4)");
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
        if (format >= GLOBALS_FROM) {
            // 26.3's shaders include with #include, earlier ones import with #moj_import
            String include = vertex.contains("#include") ? "#include" : "#moj_import";
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

    /** The uniform the program JSON (to 1.21.1) must list for what the weaving added. */
    static final Map<String, Object> VIEW_ROTATION_UNIFORM = Map.of("name", VIEW_ROTATION, "type", "matrix3x3", "count", 9.0,
        "values", List.of(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0));
}
