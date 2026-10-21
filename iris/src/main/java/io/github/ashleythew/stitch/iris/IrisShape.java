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
     * Until 1.20.5 Minecraft puts the camera's rotation into each entity vertex, so positions are in view
     * space; set by the loader's mod for its Minecraft version.
     */
    private static volatile boolean viewSpace = false;

    private IrisShape() {
    }

    public static void viewSpace(boolean value) {
        viewSpace = value;
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
            // shader packs never draw into screens, so this is always the world
            .inScreen("false")
            // GLSL 330 without separate shader objects: varyings are matched by name, no layout locations
            .layouts(false)
            // shader packs' clock, in seconds: a Minecraft day is 1200 of them
            .uniform("game_time", "fract(frameTimeCounter / 1200.0)", "frameTimeCounter", "uniform float frameTimeCounter;");
        if (viewSpace) {
            // the world's up, turned by the camera that drew this pass
            String view = shadow ? "shadowModelView" : "gbufferModelView";
            shape.orientation("normalize(" + view + "[1].xyz)", "1.0");
            if (!Pattern.compile("uniform\\s+mat4\\s+" + view + "\\s*;").matcher(vertex).find()) {
                shape.preamble("uniform mat4 " + view + ";\n");
            }
        } else {
            // positions relative to the camera, along the world's axes
            shape.orientation("vec3(0.0, 1.0, 0.0)", "1.0").modelSpace();
        }
        if (shadow) {
            shape.shadow();
        }
        return shape;
    }
}
