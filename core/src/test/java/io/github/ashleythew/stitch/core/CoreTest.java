package io.github.ashleythew.stitch.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreTest {

    @Test
    void jsonRoundTrips() {
        Object value = Json.parse("{\"a\": [1, 2.5, true, null, \"x\\u00e9\"], \"b\": {}}");
        assertEquals(value, Json.parse(Json.write(value, "  ")));
    }

    @Test
    void jsonRejectsTrailingText() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} x"));
    }

    private static Stitch stitch(String json, Map<String, String> files) {
        return Stitch.read("test:s", json, files::get);
    }

    @Test
    void stitchReadsAndDefaults() {
        Stitch s = stitch("{\"format\": 1, \"targets\": [\"armor\"], \"vertex\": \"test:v.glsl\"}",
            Map.of("test:v.glsl", "void stitch_vertex(inout StitchVertex v) {}"));
        assertEquals(List.of("armor"), s.targets());
        assertEquals(Set.of("main", "shadow"), s.passes());
        assertEquals("test_s", s.prefix());
    }

    @Test
    void stitchRejectsWhatTheSpecForbids() {
        Map<String, String> files = Map.of("test:v.glsl", "#version 150\nvoid stitch_vertex(inout StitchVertex v) {}");
        assertThrows(StitchException.class, () -> stitch("{\"format\": 2, \"targets\": [\"armor\"], \"vertex\": \"test:v.glsl\"}", files));
        assertThrows(StitchException.class, () -> stitch("{\"format\": 1, \"targets\": [\"nope\"], \"vertex\": \"test:v.glsl\"}", files));
        assertThrows(StitchException.class, () -> stitch("{\"format\": 1, \"targets\": [\"armor\"], \"vertex\": \"test:v.glsl\"}", files));
        assertThrows(StitchException.class, () -> stitch("{\"format\": 1, \"targets\": [\"armor\"], \"vertex\": \"test:v.glsl\","
            + " \"varyings\": [{\"name\": \"n\", \"type\": \"int\"}]}", Map.of("test:v.glsl", "")));
    }

    @Test
    void tokensRebuildTheText() {
        String glsl = "#version 150\n// hi\nuniform sampler2D S; /* c */ float a = 1.5e-3;\nvoid main() { a += 0x1F; }\n";
        assertEquals(glsl, Glsl.join(Glsl.tokens(glsl)));
    }

    @Test
    void declaredNames() {
        String glsl = """
            #define SIZE 4
            const int TABLE[2] = int[](1, 2);
            const float A = 1.0, B = 2.0;
            struct Box { vec3 a; };
            vec3 helper(vec3 p) { return p; }
            void stitch_vertex(inout StitchVertex v) { v.position = helper(v.position); }
            """;
        assertEquals(Set.of("SIZE", "TABLE", "A", "B", "Box", "helper", "stitch_vertex"), Glsl.declared(Glsl.tokens(glsl)));
    }

    @Test
    void mainAfterAnotherFunction() {
        // 26.3's entity shader: a function before main, which a struct check once swallowed main into
        String glsl = "#version 330\nvec4 f(vec4 c) {\n    return c;\n}\n\nvoid main() {\n    gl_FragColor = f(vec4(1.0));\n}\n";
        assertNotNull(Glsl.main(Glsl.tokens(glsl)));
    }

    @Test
    void interfaceBlockWithInstanceName() {
        String glsl = "layout(std140) uniform Block { mat4 M; } block;\nvoid main() {}\n";
        assertNotNull(Glsl.main(Glsl.tokens(glsl)));
    }

    private static final String VERTEX = """
        #version 150
        in vec3 Position;
        in vec4 Color;
        in vec2 UV0;
        uniform mat4 ProjMat;
        out vec2 texCoord0;
        out vec4 vertexColor;
        void main() {
            gl_Position = ProjMat * vec4(Position, 1.0);
            vertexColor = Color;
            texCoord0 = UV0;
        }
        """;
    private static final String FRAGMENT = """
        #version 150
        uniform sampler2D Sampler0;
        in vec2 texCoord0;
        in vec4 vertexColor;
        out vec4 fragColor;
        void main() {
            fragColor = texture(Sampler0, texCoord0) * vertexColor;
        }
        """;

    @Test
    void finalColour() {
        Stitch s = stitch("{\"format\": 1, \"targets\": [\"text_world\"], \"fragment\": \"test:f\", \"uses\": [\"final_color\"]}",
            Map.of("test:f", "bool stitch_final(out vec4 color) { color = vec4(1.0); return gl_FragCoord.x > 2.0; }"));
        ProgramShape shape = new ProgramShape().input("position", "Position").albedo("Sampler0", Set.of("texture"))
            .finalColor("fragColor = $C;");
        String f = Weaver.weave(VERTEX, FRAGMENT, shape, List.of(s)).fragment();
        // claimed at the start of main, before the program samples or lights anything; no texel function to call
        int claim = f.indexOf("if (stitch_final_all(stitch_final_color)) {");
        assertTrue(claim > f.indexOf("void main()"));
        assertTrue(claim < f.indexOf("texture(Sampler0"));
        assertTrue(f.contains("fragColor = stitch_final_color;"));
        assertTrue(f.contains("if (stitch_test_s_stitch_final(c)) {"));
        assertFalse(f.contains("stitch_test_s_stitch_texel("));
        // a shader pack's: at the end of main, over what the program wrote, and main goes on to its end
        String end = Weaver.weave(VERTEX, FRAGMENT, new ProgramShape().input("position", "Position")
            .albedo("Sampler0", Set.of("texture")).finalColor("iris_FragData0 = $C;", true), List.of(s)).fragment();
        assertTrue(end.indexOf("iris_FragData0 = stitch_final_color;") > end.indexOf("fragColor = stitch_texel_all("));
        assertFalse(end.contains("return;"));
        // a reader without it never calls the function
        String plain = Weaver.weave(VERTEX, FRAGMENT, new ProgramShape().input("position", "Position")
            .albedo("Sampler0", Set.of("texture")), List.of(s)).fragment();
        assertFalse(plain.contains("stitch_final_all"));
    }

    @Test
    void screenPositionAndViewRotation() {
        Stitch s = stitch("{\"format\": 1, \"targets\": [\"text_world\"], \"core\": false, \"vertex\": \"test:v\","
                + " \"uses\": [\"screen_position\", \"view_rotation\"]}",
            Map.of("test:v", "void stitch_vertex(inout StitchVertex v) {\n#ifdef STITCH_HAS_SCREEN_POSITION\n"
                + "    stitch_set_screen_position((stitch_view_rotation() * v.position).xy, 1.0);\n#endif\n}"));
        assertFalse(s.core());
        assertTrue(stitch("{\"format\": 1, \"targets\": [\"armor\"], \"vertex\": \"test:v\"}", Map.of("test:v", "")).core());
        ProgramShape shape = new ProgramShape().input("position", "Position").input("uv", "UV0")
            .screenPosition("return vec4(p, -0.999 - 0.00002 * layer, 1.0);").viewRotation("mat3(gbufferModelView)");
        Weaver.Woven w = Weaver.weave(VERTEX, FRAGMENT, shape, List.of(s));
        assertEquals(List.of("test:s"), w.applied());
        // the screen's position replaces the program's at the end of main, before a hidden vertex is moved away
        String v = w.vertex();
        assertTrue(v.contains("#define STITCH_HAS_SCREEN_POSITION"));
        assertTrue(v.contains("mat3 stitch_view_rotation() { return mat3(gbufferModelView); }"));
        assertTrue(v.contains("stitch_on_screen = false;"));
        int screen = v.indexOf("if (stitch_on_screen) {");
        assertTrue(screen > v.indexOf("gl_Position = ProjMat"));
        assertTrue(screen < v.indexOf("if (stitch_hidden) {"));
        // a program that can't give it leaves the functions out, and the stitch's #ifdef with them
        Weaver.Woven plain = Weaver.weave(VERTEX, FRAGMENT, new ProgramShape().input("position", "Position"), List.of(s));
        assertFalse(plain.vertex().contains("stitch_on_screen"));
    }

    @Test
    void eyePosition() {
        Stitch s = stitch("{\"format\": 1, \"targets\": [\"armor\"], \"vertex\": \"test:v\", \"fragment\": \"test:f\","
                + " \"uses\": [\"eye_position\", \"fragment_depth\"]}",
            Map.of("test:v", "void stitch_vertex(inout StitchVertex v) {\n#ifdef STITCH_HAS_EYE_POSITION\n"
                + "    v.position -= stitch_eye_position();\n#endif\n}",
                "test:f", "void stitch_fragment(inout StitchFragment f) {\n#ifdef STITCH_HAS_EYE_POSITION\n"
                + "    stitch_set_depth(stitch_eye_position());\n#endif\n}"));
        String matrices = "uniform mat4 ProjMat;\nuniform mat4 ModelViewMat;";
        ProgramShape shape = new ProgramShape().input("position", "Position")
            .fragmentDepth("return p;", matrices).eyePosition("return vec3(ProjMat[3].xyz);", matrices);
        Weaver.Woven w = Weaver.weave(VERTEX, FRAGMENT, shape, List.of(s));
        assertEquals(List.of("test:s"), w.applied());
        // in both functions, the fragment shader given the matrices it reads once
        assertTrue(w.vertex().contains("#define STITCH_HAS_EYE_POSITION"));
        assertTrue(w.vertex().contains("vec3 stitch_eye_position() {\n    return vec3(ProjMat[3].xyz);\n}"));
        String f = w.fragment();
        assertTrue(f.contains("#define STITCH_HAS_EYE_POSITION"));
        assertTrue(f.contains("vec3 stitch_eye_position() {"));
        assertEquals(f.indexOf("uniform mat4 ProjMat;"), f.lastIndexOf("uniform mat4 ProjMat;"));
        // a program that can't give it leaves it out, and the stitch's #ifdef with it
        Weaver.Woven plain = Weaver.weave(VERTEX, FRAGMENT, new ProgramShape().input("position", "Position"), List.of(s));
        assertFalse(plain.vertex().contains("vec3 stitch_eye_position() {"));
        assertFalse(plain.fragment().contains("vec3 stitch_eye_position() {"));
        assertFalse(plain.fragment().contains("#define STITCH_HAS_EYE_POSITION"));
    }

    @Test
    void weavesInputsAndSamples() {
        Stitch s = stitch("{\"format\": 1, \"targets\": [\"armor\"], \"vertex\": \"test:v\", \"fragment\": \"test:f\","
                + " \"varyings\": [{\"name\": \"tint\", \"type\": \"vec4\"}], \"requires\": [\"texture_size\"]}",
            Map.of("test:v", "const float K = 2.0;\nvoid stitch_vertex(inout StitchVertex v) { v.position *= K; tint = v.color; }",
                "test:f", "vec4 stitch_texel(StitchFragment f, vec4 texel) { return texel * tint; }"));
        ProgramShape shape = new ProgramShape().input("position", "Position").input("color", "Color").input("uv", "UV0")
            .albedo("Sampler0", Set.of("texture"));
        Weaver.Woven w = Weaver.weave(VERTEX, FRAGMENT, shape, List.of(s));
        assertEquals(List.of("test:s"), w.applied());
        // the program reads the stitched values; the inputs are read once, in stitch_run
        assertTrue(w.vertex().contains("vec4(stitch_position, 1.0)"));
        assertTrue(w.vertex().contains("vertexColor = stitch_color;"));
        assertTrue(w.vertex().contains("in vec3 Position;"));
        assertTrue(w.vertex().contains("stitch_run();"));
        // the stitch's own names are prefixed, its varying declared on both sides
        assertTrue(w.vertex().contains("const float stitch_test_s_K = 2.0;"));
        assertTrue(w.vertex().contains("out vec4 stitch_test_s_tint;"));
        assertTrue(w.fragment().contains("in vec4 stitch_test_s_tint;"));
        assertTrue(w.fragment().contains("stitch_texel_all(texture(Sampler0, texCoord0), vec2(texCoord0))"));
        // the sampler is declared in the vertex shader, where the helpers read it
        assertTrue(w.vertex().contains("uniform sampler2D Sampler0;"));
    }

    @Test
    void stagesWovenApartMatchWovenTogether() {
        Stitch s = stitch("{\"format\": 1, \"targets\": [\"armor\"], \"vertex\": \"test:v\", \"fragment\": \"test:f\","
                + " \"varyings\": [{\"name\": \"tint\", \"type\": \"vec4\"}], \"requires\": [\"texture_size\"]}",
            Map.of("test:v", "void stitch_vertex(inout StitchVertex v) { tint = v.color; }",
                "test:f", "vec4 stitch_texel(StitchFragment f, vec4 texel) { return texel * tint; }"));
        ProgramShape shape = new ProgramShape().input("position", "Position").input("color", "Color").input("uv", "UV0")
            .albedo("Sampler0", Set.of("texture"));
        Weaver.Woven both = Weaver.weave(VERTEX, FRAGMENT, shape, List.of(s));
        assertEquals(both.vertex(), Weaver.weave(VERTEX, null, shape, List.of(s)).vertex());
        assertEquals(both.fragment(), Weaver.weave(null, FRAGMENT, shape, List.of(s)).fragment());
    }

    @Test
    void sharedProgramKeepsTheStitchesToItsTarget() {
        Stitch s = stitch("{\"format\": 1, \"targets\": [\"armor\"], \"vertex\": \"test:v\", \"fragment\": \"test:f\","
                + " \"requires\": [\"texture_size\"], \"uses\": [\"fragment_depth\"]}",
            Map.of("test:v", "const float K = 2.0;\nvoid stitch_vertex(inout StitchVertex v) { v.position *= K; }",
                "test:f", "vec4 stitch_texel(StitchFragment f, vec4 texel) { stitch_set_depth(f.position); return texel; }"));
        ProgramShape shape = new ProgramShape().input("position", "Position").input("uv", "UV0")
            .albedo("Sampler0", Set.of("texture")).fragmentPosition().fragmentDepth("return 0.5;", "uniform mat4 ProjMat;")
            .guard("defined(ARMOUR)");
        Weaver.Woven w = Weaver.weave(VERTEX, FRAGMENT, shape, List.of(s));
        assertEquals(List.of("test:s"), w.applied());
        // with the define, as without a guard
        String armour = Preprocessor.resolve("#define ARMOUR\n" + w.vertex(), Set.of());
        assertTrue(armour.contains("const float stitch_test_s_K = 2.0;"));
        assertTrue(armour.contains("stitch_test_s_stitch_vertex(v);"));
        assertTrue(Preprocessor.resolve("#define ARMOUR\n" + w.fragment(), Set.of()).contains("gl_FragDepth ="));
        // without it, neither the stitch nor a depth write: the inputs and the texel pass through
        String vertex = Preprocessor.resolve(w.vertex(), Set.of());
        String fragment = Preprocessor.resolve(w.fragment(), Set.of());
        assertFalse(vertex.contains("stitch_test_s_"));
        assertFalse(fragment.contains("stitch_test_s_"));
        assertFalse(fragment.contains("gl_FragDepth"));
        assertTrue(vertex.contains("stitch_position = v.position;"));
        assertTrue(fragment.contains("return texel;"));
        assertNotNull(Glsl.main(Glsl.tokens(vertex)));
        assertNotNull(Glsl.main(Glsl.tokens(fragment)));
    }

    @Test
    void gameTimeInBothStagesOrStandingStill() {
        Stitch s = stitch("{\"format\": 1, \"targets\": [\"armor\"], \"vertex\": \"test:v\", \"fragment\": \"test:f\","
                + " \"uniforms\": [\"game_time\"]}",
            Map.of("test:v", "void stitch_vertex(inout StitchVertex v) { v.position.y += stitch_game_time; }",
                "test:f", "vec4 stitch_texel(StitchFragment f, vec4 texel) { return texel * stitch_game_time; }"));
        ProgramShape shape = new ProgramShape().input("position", "Position").albedo("Sampler0", Set.of("texture"))
            .uniform("game_time", "GameTime", "GameTime", "uniform float GameTime;");
        Weaver.Woven w = Weaver.weave(VERTEX, FRAGMENT, shape, List.of(s));
        for (String stage : List.of(w.vertex(), w.fragment())) {
            assertTrue(stage.contains("uniform float GameTime;"));
            assertTrue(stage.contains("#define stitch_game_time (GameTime)"));
        }
        // declared once, where the program already has it
        Weaver.Woven again = Weaver.weave(VERTEX.replace("uniform mat4 ProjMat;", "uniform mat4 ProjMat;\nuniform float GameTime;"),
            FRAGMENT, shape, List.of(s));
        assertEquals(1, again.vertex().split("uniform float GameTime;", -1).length - 1);
        // a reader without a clock still applies the stitch, standing still
        Weaver.Woven still = Weaver.weave(VERTEX, FRAGMENT, new ProgramShape().input("position", "Position")
            .albedo("Sampler0", Set.of("texture")), List.of(s));
        assertEquals(List.of("test:s"), still.applied());
        assertTrue(still.fragment().contains("#define stitch_game_time (0.0)"));
    }

    @Test
    void preprocessorKeepsOneStage() {
        String glsl = """
            #version 150 compatibility
            #define VERTEX_SHADER
            #define QUALITY 2 // a setting
            /* #ifdef VERTEX_SHADER
            in a comment */
            #ifdef FRAGMENT_SHADER
            void main() { fragment(); }
            #else
            #if QUALITY >= 2 && defined(VERTEX_SHADER) && !defined GL_ARB_nope
            void main() { vertex(); }
            #elif QUALITY == 1
            void main() { low(); }
            #endif
            #endif
            """;
        String out = Preprocessor.resolve(glsl, Set.of());
        assertTrue(out.contains("void main() { vertex(); }"));
        assertFalse(out.contains("fragment();"));
        assertFalse(out.contains("low();"));
        assertFalse(out.contains("#if"));
        assertTrue(out.contains("#define QUALITY 2"));
        // every line keeps its number
        assertEquals(glsl.split("\n", -1).length, out.split("\n", -1).length);
        assertNotNull(Glsl.main(Glsl.tokens(out)));
    }

    @Test
    void preprocessorDecidesExtensionsOnlyWhenTold() {
        String glsl = "#ifdef GL_ARB_shader_draw_parameters\nint a;\n#else\nint b;\n#endif\n";
        assertTrue(Preprocessor.resolve(glsl, Set.of("GL_ARB_shader_draw_parameters")).contains("int a;"));
        assertTrue(Preprocessor.resolve(glsl, Set.of()).contains("int b;"));
        assertThrows(IllegalArgumentException.class, () -> Preprocessor.resolve(glsl, null));
        // a condition in a skipped block is never asked
        assertTrue(Preprocessor.resolve("#if 0\n#ifdef GL_ARB_x\n#endif\n#endif\nint c;\n", null).contains("int c;"));
        assertThrows(IllegalArgumentException.class, () -> Preprocessor.resolve("#define F(x) x\n#if F(1)\n#endif\n", Set.of()));
    }

    @Test
    void skipsWhatTheProgramCantGive() {
        Stitch s = stitch("{\"format\": 1, \"targets\": [\"armor\"], \"vertex\": \"test:v\", \"requires\": [\"vertex_corner\"]}",
            Map.of("test:v", "void stitch_vertex(inout StitchVertex v) {}"));
        Weaver.Woven w = Weaver.weave(VERTEX, FRAGMENT, new ProgramShape(), List.of(s));
        assertTrue(w.applied().isEmpty());
        assertTrue(w.skipped().containsKey("test:s"));
        assertEquals(VERTEX, w.vertex());
    }

    @Test
    void usesWhatTheProgramCanGive() {
        Stitch s = stitch("{\"format\": 1, \"targets\": [\"armor\"], \"vertex\": \"test:v\", \"fragment\": \"test:f\","
                + " \"uses\": [\"fragment_depth\"]}",
            Map.of("test:v", "void stitch_vertex(inout StitchVertex v) {}",
                "test:f", "vec4 stitch_texel(StitchFragment f, vec4 texel) {\n#ifdef STITCH_HAS_FRAGMENT_DEPTH\n"
                    + "    stitch_set_depth(f.position);\n#endif\n    return texel;\n}"));
        ProgramShape shape = new ProgramShape().input("position", "Position").albedo("Sampler0", Set.of("texture"))
            .fragmentPosition().fragmentDepth("return p.z;", "uniform mat4 ProjMat;");
        Weaver.Woven w = Weaver.weave(VERTEX, FRAGMENT, shape, List.of(s));
        assertEquals(List.of("test:s"), w.applied());
        for (String stage : List.of(w.vertex(), w.fragment())) {
            assertTrue(stage.contains("#define STITCH_HAS_FRAGMENT_DEPTH"));
            assertTrue(stage.contains("#define STITCH_HAS_FRAGMENT_POSITION"));
        }
        // the fragment's point comes from the vertex, and its depth is written last in main
        assertTrue(w.vertex().contains("out vec3 stitch_fragment_position;"));
        assertTrue(w.fragment().contains("in vec3 stitch_fragment_position;"));
        assertTrue(w.fragment().contains("float stitch_depth_of(vec3 p) {\n    return p.z;\n}"));
        String main = w.fragment().substring(w.fragment().indexOf("void main()"));
        assertTrue(main.contains("gl_FragDepth = stitch_depth_set ? stitch_depth_of(stitch_depth_position) : gl_FragCoord.z;\n}"));
        // ProjMat is declared once, where the program has it already
        assertEquals(1, w.fragment().split("uniform mat4 ProjMat;", -1).length - 1);
        // without the capability the stitch still applies, and leaves the depth alone
        Weaver.Woven plain = Weaver.weave(VERTEX, FRAGMENT, new ProgramShape().input("position", "Position")
            .albedo("Sampler0", Set.of("texture")), List.of(s));
        assertEquals(List.of("test:s"), plain.applied());
        assertFalse(plain.fragment().contains("gl_FragDepth"));
        assertFalse(plain.fragment().contains("#define STITCH_HAS_FRAGMENT_DEPTH"));
    }

    @Test
    void shadowOnlyWhereAsked() {
        Stitch s = stitch("{\"format\": 1, \"targets\": [\"armor\"], \"vertex\": \"test:v\", \"passes\": [\"main\"]}",
            Map.of("test:v", "void stitch_vertex(inout StitchVertex v) {}"));
        Weaver.Woven w = Weaver.weave(VERTEX, FRAGMENT, new ProgramShape().shadow(), List.of(s));
        assertFalse(w.applied().contains("test:s"));
    }
}
