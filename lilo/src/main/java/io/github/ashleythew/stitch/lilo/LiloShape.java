package io.github.ashleythew.stitch.lilo;

import io.github.ashleythew.stitch.core.ProgramShape;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * How LILO's programs read what stitches need. It translates a shader pack's programs to Vulkan GLSL 460 against the
 * game's own pipelines, so its vertices have Minecraft's attribute names and its matrices the game's.
 */
final class LiloShape {

    private LiloShape() {
    }

    private static boolean hasInput(String vertex, String name) {
        return Pattern.compile("\\bin\\s+\\w+\\s+" + name + "\\s*;").matcher(vertex).find();
    }

    static ProgramShape of(boolean shadow, String vertex, String fragment) {
        ProgramShape shape = new ProgramShape();
        String[][] inputs = {{"position", "Position"}, {"uv", "UV0"}, {"color", "Color"},
            {"normal", "Normal"}, {"light", "UV2"}, {"overlay", "UV1"}};
        for (String[] in : inputs) {
            if (hasInput(vertex, in[1])) {
                shape.input(in[0], in[1]);
            }
        }
        // a draw's vertices count from its base vertex here, which needn't be a multiple of 4; LILO turns draw parameters on
        shape.albedo("Sampler0", Set.of("texture", "texture2D"))
            .corner("((gl_VertexIndex - gl_BaseVertex) % 4)")
            .vertexIndex("(gl_VertexIndex - gl_BaseVertex)")
            // shader packs never draw into screens, so this is always the world
            .inScreen("false")
            // varyings are matched by their order of declaration, and a vertex shader's own come after what it shares
            .layouts(false)
            .varyingsFirst()
            // a translated program's own main becomes lilo_main
            .mainIsWrapper()
            // positions relative to the camera, along the world's axes
            .orientation("vec3(0.0, 1.0, 0.0)", "1.0")
            .modelSpace();
        // the game's clock and screen, in its Globals block, which every pipeline that draws entities declares
        if (vertex.contains("uniform Globals")) {
            shape.uniform("game_time", "GameTime", "GameTime", "");
            shape.uniform("screen_size", "ScreenSize", "ScreenSize", "");
        }
        if (hasInput(vertex, "Position") && hasInput(vertex, "UV0")) {
            shape.quadCorners("Position", "UV0", "(gl_VertexIndex - gl_BaseVertex)",
                "#extension GL_KHR_shader_subgroup_basic : enable\n#extension GL_KHR_shader_subgroup_shuffle : enable");
        }
        // no tangent in the game's entity vertices
        if (shadow) {
            shape.shadow();
        } else if (vertex.contains("ProjMat") && vertex.contains("ModelViewMat")) {
            // the game's depth, as this fragment's own was found
            shape.fragmentPosition().fragmentDepth("vec4 c = ProjMat * ModelViewMat * vec4(p, 1.0);\n    return c.z / c.w;", null);
        }
        return shape;
    }
}
