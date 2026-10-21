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
        return shape;
    }

    /** The uniform the program JSON (to 1.21.1) must list for what the weaving added. */
    static final Map<String, Object> VIEW_ROTATION_UNIFORM = Map.of("name", VIEW_ROTATION, "type", "matrix3x3", "count", 9.0,
        "values", List.of(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0));
}
