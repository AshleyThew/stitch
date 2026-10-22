package io.github.ashleythew.stitch.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * One stitch: its definition (docs/SPEC.md) with its GLSL read, checked. Built by {@link #read}, which
 * fails with a message naming the stitch and what is wrong.
 */
public final class Stitch {

    /** The formats this reader knows. */
    public static final int FORMAT = 1;

    public static final Set<String> TARGETS = Set.of("armor", "entity", "item_world", "item_gui", "text_world", "text_gui", "glint");
    public static final Set<String> UNIFORMS = Set.of("game_time", "screen_size");
    public static final Set<String> CAPABILITIES = Set.of("vertex_corner", "texture_size", "position_model_space", "orientation", "shadow_pass",
        "quad_corners", "tangent", "fragment_position", "fragment_depth", "vertex_index");
    public static final Set<String> PASSES = Set.of("main", "shadow");
    private static final Set<String> TYPES = Set.of("float", "vec2", "vec3", "vec4", "int", "ivec2", "ivec3", "ivec4");
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    public static final int MAX_VARYINGS = 4;
    public static final int MAX_SOURCE = 64 * 1024;

    public record Varying(String name, String type, boolean flat) {
    }

    private final String id;
    private final List<String> targets;
    private final String vertex;
    private final String fragment;
    private final List<Varying> varyings;
    private final Set<String> uniforms;
    private final Set<String> requires;
    private final Set<String> uses;
    private final Set<String> passes;
    private final int order;

    private Stitch(String id, List<String> targets, String vertex, String fragment, List<Varying> varyings,
                   Set<String> uniforms, Set<String> requires, Set<String> uses, Set<String> passes, int order) {
        this.id = id;
        this.targets = targets;
        this.vertex = vertex;
        this.fragment = fragment;
        this.varyings = varyings;
        this.uniforms = uniforms;
        this.requires = requires;
        this.uses = uses;
        this.passes = passes;
        this.order = order;
    }

    /**
     * The stitch whose definition is this JSON, its GLSL read through {@code source} (a resource location
     * to its text, or null if there is none).
     */
    public static Stitch read(String id, String json, Function<String, String> source) {
        if (!ID.matcher(id).matches()) {
            throw new StitchException(id, "isn't a valid id (namespace:path, lower case)");
        }
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (IllegalArgumentException e) {
            throw new StitchException(id, e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new StitchException(id, "the definition isn't a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) raw;
        Object format = map.get("format");
        if (!(format instanceof Double f) || f != FORMAT) {
            throw new StitchException(id, "format " + format + " isn't one this reader knows (" + FORMAT + ")");
        }
        List<String> targets = strings(id, map, "targets", TARGETS, true);
        if (targets.isEmpty()) {
            throw new StitchException(id, "it has no targets");
        }
        String vertex = glsl(id, map, "vertex", source);
        String fragment = glsl(id, map, "fragment", source);
        if (vertex == null && fragment == null) {
            throw new StitchException(id, "it has neither a vertex nor a fragment file");
        }
        List<Varying> varyings = new ArrayList<>();
        Object v = map.get("varyings");
        if (v != null) {
            if (!(v instanceof List<?> list)) {
                throw new StitchException(id, "varyings isn't a list");
            }
            Set<String> names = new LinkedHashSet<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m) || !(m.get("name") instanceof String name) || !(m.get("type") instanceof String type)) {
                    throw new StitchException(id, "a varying needs a name and a type");
                }
                boolean flat = Boolean.TRUE.equals(m.get("flat"));
                if (!NAME.matcher(name).matches() || !names.add(name)) {
                    throw new StitchException(id, "varying " + name + " isn't a valid or unique name");
                }
                if (!TYPES.contains(type)) {
                    throw new StitchException(id, "varying " + name + " has type " + type + ", not one of " + TYPES);
                }
                if (type.startsWith("i") && !flat) {
                    throw new StitchException(id, "varying " + name + " is an integer, so it must be flat");
                }
                varyings.add(new Varying(name, type, flat));
            }
            if (varyings.size() > MAX_VARYINGS) {
                throw new StitchException(id, "it has " + varyings.size() + " varyings, more than " + MAX_VARYINGS);
            }
        }
        Set<String> uniforms = new LinkedHashSet<>(strings(id, map, "uniforms", UNIFORMS, false));
        Set<String> requires = new LinkedHashSet<>(strings(id, map, "requires", null, false));
        // capabilities it takes where the reader has them, checking STITCH_HAS_<NAME>, and does without
        Set<String> uses = new LinkedHashSet<>(strings(id, map, "uses", CAPABILITIES, false));
        List<String> passes = strings(id, map, "passes", PASSES, false);
        Object order = map.get("order");
        if (order != null && !(order instanceof Double)) {
            throw new StitchException(id, "order isn't a number");
        }
        return new Stitch(id, Collections.unmodifiableList(targets), vertex, fragment, Collections.unmodifiableList(varyings),
            Collections.unmodifiableSet(uniforms), Collections.unmodifiableSet(requires), Collections.unmodifiableSet(uses),
            Collections.unmodifiableSet(new LinkedHashSet<>(passes.isEmpty() ? List.of("main", "shadow") : passes)),
            order == null ? 0 : ((Double) order).intValue());
    }

    private static List<String> strings(String id, Map<String, Object> map, String key, Set<String> allowed, boolean required) {
        Object value = map.get(key);
        if (value == null) {
            if (required) {
                throw new StitchException(id, key + " is missing");
            }
            return new ArrayList<>();
        }
        if (!(value instanceof List<?> list)) {
            throw new StitchException(id, key + " isn't a list");
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String s)) {
                throw new StitchException(id, key + " has a value that isn't a string");
            }
            if (allowed != null && !allowed.contains(s)) {
                throw new StitchException(id, key + " has " + s + ", not one of " + allowed);
            }
            out.add(s);
        }
        return out;
    }

    private static String glsl(String id, Map<String, Object> map, String key, Function<String, String> source) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String location) || !ID.matcher(location).matches()) {
            throw new StitchException(id, key + " isn't a resource location");
        }
        String text = source.apply(location);
        if (text == null) {
            throw new StitchException(id, key + " " + location + " doesn't exist");
        }
        if (text.length() > MAX_SOURCE) {
            throw new StitchException(id, key + " " + location + " is over " + MAX_SOURCE / 1024 + " KiB");
        }
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#version") || t.startsWith("#extension") || t.startsWith("#include") || t.startsWith("#moj_import")) {
                throw new StitchException(id, key + " has " + t.split("\\s")[0] + ", which a stitch can't use");
            }
        }
        return text;
    }

    public String id() {
        return id;
    }

    public List<String> targets() {
        return targets;
    }

    public String vertex() {
        return vertex;
    }

    public String fragment() {
        return fragment;
    }

    public List<Varying> varyings() {
        return varyings;
    }

    public Set<String> uniforms() {
        return uniforms;
    }

    public Set<String> requires() {
        return requires;
    }

    public Set<String> uses() {
        return uses;
    }

    public Set<String> passes() {
        return passes;
    }

    public int order() {
        return order;
    }

    /** A name unique to this stitch, for its prefix: example:flared_leggings -> example_flared_leggings. */
    public String prefix() {
        return id.replaceAll("[^a-z0-9]", "_");
    }

    @Override
    public String toString() {
        return id;
    }
}
