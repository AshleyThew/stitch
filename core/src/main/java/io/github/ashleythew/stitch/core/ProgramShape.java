package io.github.ashleythew.stitch.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What a reader knows about the program it weaves into: where the vertex's inputs come from, which
 * sampler holds the albedo, and how to work out what stitches may ask for. Each reader (the compiler,
 * for Minecraft's core shaders; the mod, for Iris's or OptiFine's programs) describes its programs with
 * one of these.
 */
public final class ProgramShape {

    /** The program's input for each StitchVertex field, by name: position, uv, color, normal, light, overlay. */
    final Map<String, String> inputs = new LinkedHashMap<>();
    String sampler = "Sampler0";
    Set<String> textureFunctions = Set.of("texture");
    String corner = null;
    String up = null;
    String handedness = null;
    String inScreen = "false";
    /** Each uniform the reader can give, by its name in the spec (game_time, screen_size). */
    final Map<String, Uniform> uniforms = new LinkedHashMap<>();
    boolean shadow = false;
    boolean layouts = false;
    String vertexHeader = null;
    String preamble = "";
    final java.util.HashSet<String> capabilities = new java.util.HashSet<>();

    /** The program reads this StitchVertex field from that input (Position, vaPosition, gl_Vertex...). */
    public ProgramShape input(String field, String name) {
        inputs.put(field, name);
        return this;
    }

    /** The sampler holding the albedo (base colour), and the functions that sample it. */
    public ProgramShape albedo(String sampler, Set<String> functions) {
        this.sampler = sampler;
        this.textureFunctions = functions;
        capabilities.add("texture_size");
        return this;
    }

    /** A GLSL expression for the vertex's corner, 0-3 in Minecraft's quad order. */
    public ProgramShape corner(String expression) {
        this.corner = expression;
        capabilities.add("vertex_corner");
        return this;
    }

    /** GLSL expressions for up, in position's space, and the handedness of that space. */
    public ProgramShape orientation(String up, String handedness) {
        this.up = up;
        this.handedness = handedness;
        capabilities.add("orientation");
        return this;
    }

    /** A GLSL expression true when drawing into a screen. */
    public ProgramShape inScreen(String expression) {
        this.inScreen = expression;
        return this;
    }

    /**
     * A uniform from the spec's list (game_time, screen_size): the GLSL expression for it, and what declares
     * what that expression reads (a uniform line, or an import), added to each stage that doesn't already
     * declare the named uniform or carry the declaration.
     */
    public ProgramShape uniform(String name, String expression, String reads, String declaration) {
        uniforms.put(name, new Uniform(expression, reads, declaration));
        return this;
    }

    record Uniform(String expression, String reads, String declaration) {
    }

    /** Position is in model space (from 1.20.5; view space before). */
    public ProgramShape modelSpace() {
        capabilities.add("position_model_space");
        return this;
    }

    /** This is a shadow-pass program. */
    public ProgramShape shadow() {
        this.shadow = true;
        capabilities.add("shadow_pass");
        return this;
    }

    /** Its varyings need explicit layout locations. */
    public ProgramShape layouts(boolean layouts) {
        this.layouts = layouts;
        return this;
    }

    /** Text to put first in the vertex shader in place of its #version line (e.g. to raise the version). */
    public ProgramShape vertexHeader(String header) {
        this.vertexHeader = header;
        return this;
    }

    /** GLSL put before the helpers in the vertex shader: macros its expressions use. */
    public ProgramShape preamble(String glsl) {
        this.preamble = glsl;
        return this;
    }

    public Set<String> capabilities() {
        return capabilities;
    }
}
