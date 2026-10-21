package io.github.ashleythew.stitch.optifine;

import io.github.ashleythew.stitch.core.ProgramShape;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How OptiFine's programs read what stitches need. OptiFine turns a shader pack's fixed-function names
 * into Minecraft's own vertex attributes (gl_Vertex becomes vaPosition, gl_Color vaColor...) before
 * Stitch sees the text, so these are the names to read.
 */
public final class OptiFineShape {

    /** The names shader packs give the albedo sampler; OptiFine renames "texture" to gtexture. */
    private static final List<String> ALBEDO = List.of("gtexture", "tex", "texture");

    /**
     * Until 1.20.5 Minecraft puts the camera's rotation into each entity vertex, so positions are in view
     * space; set by the loader's mod for its Minecraft version.
     */
    private static volatile boolean viewSpace = false;

    private OptiFineShape() {
    }

    public static void viewSpace(boolean value) {
        viewSpace = value;
    }

    static ProgramShape of(String program, String source, boolean vertex) {
        ProgramShape shape = new ProgramShape();
        String[][] inputs = {{"position", "vaPosition"}, {"uv", "vaUV0"}, {"color", "vaColor"},
            {"normal", "vaNormal"}, {"light", "vaUV2"}, {"overlay", "vaUV1"}};
        for (String[] in : inputs) {
            // only the vertex shader has inputs; the fragment shader's shape needs none of them
            if (!vertex || Pattern.compile("\\b(in|attribute)\\s+\\w+\\s+" + in[1] + "\\s*;").matcher(source).find()) {
                shape.input(in[0], in[1]);
            }
        }
        String sampler = ALBEDO.stream()
            .filter(s -> Pattern.compile("uniform\\s+sampler2D\\s+" + s + "\\s*;").matcher(source).find())
            .findFirst().orElse("gtexture");
        boolean shadow = program.equals("shadow") || program.startsWith("shadow_");
        shape.albedo(sampler, Set.of("texture", "texture2D"))
            // Minecraft's own buffers, drawn in GL: four vertices a quad, from the draw's start
            .corner("(gl_VertexID % 4)")
            .inScreen("false")
            // varyings are matched by name, no layout locations
            .layouts(false);
        if (viewSpace) {
            // the world's up, turned by the camera that drew this pass
            String view = shadow ? "shadowModelView" : "gbufferModelView";
            shape.orientation("normalize(" + view + "[1].xyz)", "1.0");
            if (vertex && !Pattern.compile("uniform\\s+mat4\\s+" + view + "\\s*;").matcher(source).find()) {
                shape.preamble("uniform mat4 " + view + ";\n");
            }
        } else {
            shape.orientation("vec3(0.0, 1.0, 0.0)", "1.0").modelSpace();
        }
        if (shadow) {
            shape.shadow();
        }
        return shape;
    }
}
