package io.github.ashleythew.stitch.iris;

import io.github.ashleythew.stitch.core.ProgramShape;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** How Iris's programs read what stitches need. */
public final class IrisShape {

    /** The names shader packs give the albedo sampler, which Iris binds to the drawn texture. */
    private static final List<String> ALBEDO = List.of("gtexture", "tex", "texture");

    /**
     * A point on the screen in front of the world: shader packs draw in OpenGL's depth, nearest at -1, and a
     * layer is drawn a little nearer than the one under it.
     */
    static final String SCREEN = "return vec4(p, -0.999 - 0.00002 * layer, 1.0);";

    /**
     * From Minecraft 26.2 depth runs the other way, 0 to 1 with the nearest at 1, and Iris turns the shader
     * pack's OpenGL depth round as the last line of main, after which a stitch's position is put in: so
     * there the point's depth is given turned round already.
     */
    static final String SCREEN_REVERSED = "return vec4(p, 0.9995 + 0.00001 * layer, 1.0);";
    private static final Pattern REVERSES = Pattern.compile(
        "gl_Position\\.z\\s*=\\s*0\\.5f?\\s*\\*\\s*\\(\\s*gl_Position\\.w\\s*-\\s*gl_Position\\.z\\s*\\)");

    /**
     * Until 1.20.5 Minecraft puts the camera's rotation into each entity vertex, so positions are in view
     * space; set by the loader's mod for its Minecraft version.
     */
    private static volatile boolean viewSpace = false;

    private IrisShape() {
    }

    public static void viewSpace(boolean value) {
        viewSpace = value;
    }

    private static boolean hasInput(String vertex, String name) {
        return Pattern.compile("\\bin\\s+\\w+\\s+" + name + "\\s*;").matcher(vertex).find();
    }

    static ProgramShape of(String name, String vertex, String fragment) {
        ProgramShape shape = new ProgramShape();
        String[][] inputs = {{"position", "iris_Position"}, {"uv", "iris_UV0"}, {"color", "iris_Color"},
            {"normal", "iris_Normal"}, {"light", "iris_UV2"}, {"overlay", "iris_UV1"}};
        for (String[] in : inputs) {
            if (Pattern.compile("\\bin\\s+\\w+\\s+" + in[1] + "\\s*;").matcher(vertex).find()) {
                shape.input(in[0], in[1]);
            }
        }
        String sampler = ALBEDO.stream()
            .filter(s -> Pattern.compile("uniform\\s+sampler2D\\s+" + s + "\\s*;").matcher(fragment).find())
            .findFirst().orElse("tex");
        boolean shadow = name.startsWith("shadow_");
        shape.albedo(sampler, Set.of("texture", "texture2D"))
            // Minecraft's own buffers, drawn in GL: four vertices a quad, from the draw's start
            .corner("(gl_VertexID % 4)")
            .vertexIndex("gl_VertexID")
            // shader packs never draw into screens, so this is always the world
            .inScreen("false")
            // GLSL 330 without separate shader objects: varyings are matched by name, no layout locations
            .layouts(false)
            // shader packs' clock, in seconds: a Minecraft day is 1200 of them
            .uniform("game_time", "fract(frameTimeCounter / 1200.0)", "frameTimeCounter", "uniform float frameTimeCounter;")
            // the size shader packs draw the world at
            .uniform("screen_size", "vec2(viewWidth, viewHeight)", "viewWidth", "uniform float viewWidth;\nuniform float viewHeight;");
        String view = shadow ? "shadowModelView" : "gbufferModelView";
        if (!Pattern.compile("uniform\\s+mat4\\s+" + view + "\\s*;").matcher(vertex).find()) {
            shape.preamble("uniform mat4 " + view + ";\n");
        }
        if (!shadow) {
            // the camera's turn, which the shader pack is told; and the screen, which only the main pass draws
            shape.viewRotation("mat3(gbufferModelView)").screenPosition(REVERSES.matcher(vertex).find() ? SCREEN_REVERSED : SCREEN);
            // Iris names a program's first colour output iris_FragData0, the colour the shader pack lit: a stitch's
            // own colour goes over it at the end of main, its other outputs (normals, materials) left as they were
            if (Pattern.compile("out\\s+vec4\\s+iris_FragData0\\s*;").matcher(fragment).find()) {
                shape.finalColor("iris_FragData0 = $C;", true);
            }
        }
        if (viewSpace) {
            // the world's up, turned by the camera that drew this pass
            shape.orientation("normalize(" + view + "[1].xyz)", "1.0");
        } else {
            // positions relative to the camera, along the world's axes
            shape.orientation("vec3(0.0, 1.0, 0.0)", "1.0").modelSpace();
        }
        // a quad's corners shared by subgroup shuffles, where the program's GLSL can enable them (Iris
        // hands over its programs finished, usually 330 core) and it reads Minecraft's own buffers
        java.util.regex.Matcher version = Pattern.compile("#version\\s+(\\d+)").matcher(vertex);
        if (version.find() && Integer.parseInt(version.group(1)) >= 140
            && shape.capabilities().contains("vertex_corner") && hasInput(vertex, "iris_Position") && hasInput(vertex, "iris_UV0")) {
            shape.quadCorners("iris_Position", "iris_UV0", "gl_VertexID",
                "#extension GL_KHR_shader_subgroup_basic : enable\n#extension GL_KHR_shader_subgroup_shuffle : enable");
        }
        // Iris's entity vertices carry the quad's tangent, bound to at_tangent where a program declares it
        boolean declared = Pattern.compile("\\b(in|attribute)\\s+vec4\\s+at_tangent\\s*;").matcher(vertex).find();
        shape.tangent("at_tangent", declared ? null : "in vec4 at_tangent;");
        if (shadow) {
            shape.shadow();
        } else if (Pattern.compile("uniform\\s+mat4\\s+iris_ProjMat\\s*;").matcher(vertex).find()
            && Pattern.compile("uniform\\s+mat4\\s+iris_ModelViewMat\\s*;").matcher(vertex).find()) {
            // Iris projects its own buffers' vertices by Minecraft's matrices, renamed, into OpenGL's depth
            shape.fragmentPosition().fragmentDepth(
                "vec4 c = iris_ProjMat * iris_ModelViewMat * vec4(p, 1.0);\n    return c.z / c.w * 0.5 + 0.5;",
                "uniform mat4 iris_ProjMat;\nuniform mat4 iris_ModelViewMat;");
        }
        return shape;
    }
}
