package io.github.ashleythew.stitch.compiler;

import io.github.ashleythew.stitch.core.Json;
import io.github.ashleythew.stitch.core.Stitch;
import io.github.ashleythew.stitch.core.StitchException;
import io.github.ashleythew.stitch.core.Weaver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The Stitch compiler: a resource pack's stitches woven into Minecraft's core shaders, for every release
 * asked for, written into the pack as overlays with their pack.mcmeta entries.
 *
 * <pre>
 * java -jar stitch-compiler.jar &lt;pack folder&gt; [--versions 1.18.2+] [--output folder] [--cache folder] [--no-base] [--strict]
 * </pre>
 */
public final class Main {

    private static final String PREFIX = "stitch_";

    public static void main(String[] args) throws IOException {
        Path pack = null;
        Path output = null;
        Path cache = Path.of(System.getProperty("user.home"), ".cache", "stitch", "minecraft");
        String versions = "1.18.2+";
        boolean base = true;
        boolean strict = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--versions" -> versions = args[++i];
                case "--output" -> output = Path.of(args[++i]);
                case "--cache" -> cache = Path.of(args[++i]);
                case "--no-base" -> base = false;
                case "--strict" -> strict = true;
                case "--help", "-h" -> {
                    System.out.println("java -jar stitch-compiler.jar <pack folder> [--versions 1.18.2+] [--output folder] [--cache folder] [--no-base] [--strict]");
                    return;
                }
                default -> {
                    if (args[i].startsWith("--") || pack != null) {
                        fail("unknown argument " + args[i]);
                    }
                    pack = Path.of(args[i]);
                }
            }
        }
        if (pack == null || !Files.isDirectory(pack)) {
            fail("give the pack's folder (it has a pack.mcmeta)");
        }
        if (output == null) {
            output = pack;
        }
        int warnings = compile(pack, output, cache, versions, base);
        if (strict && warnings > 0) {
            fail(warnings + " warning(s), and --strict");
        }
    }

    /** Compiles the pack; returns how many warnings it printed. */
    static int compile(Path pack, Path output, Path cache, String versions, boolean base) throws IOException {
        List<Stitch> stitches = read(pack);
        System.out.println("stitches: " + (stitches.isEmpty() ? "none" : stitches));
        Minecraft minecraft = new Minecraft(cache);
        List<Minecraft.Release> releases = select(minecraft.releases(), versions);
        int warnings = 0;

        // the stitch overlays this wrote before go; the pack's own stay
        try (Stream<Path> old = Files.list(output)) {
            for (Path p : old.filter(p -> p.getFileName().toString().startsWith(PREFIX)).toList()) {
                delete(p);
            }
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        // a stitch only shader packs need stays out: the pack's own core shaders draw what it does
        stitches = stitches.stream().filter(Stitch::core).toList();
        List<String> targets = stitches.stream().flatMap(s -> s.targets().stream()).distinct().sorted().toList();
        for (String target : targets) {
            if (!CoreTargets.supported(target)) {
                System.out.println("WARNING: target " + target + " isn't compiled by this version of the compiler yet");
                warnings++;
                continue;
            }
            List<Stitch> forTarget = Weaver.forTarget(stitches, target);
            List<List<String>> chains = CoreTargets.SHADERS.get(target);
            for (int c = 0; c < chains.size(); c++) {
                List<String> chain = chains.get(c);
                // a target's second shader and on have overlays of their own, by that shader's name
                String label = c == 0 ? target : target + "_" + chain.get(chain.size() - 1);
                List<Group> groups = groups(minecraft, releases, target, chain, pack);
                for (int i = 0; i < groups.size(); i++) {
                    Group g = groups.get(i);
                    if (i + 1 < groups.size()) {
                        g.last = Math.max(g.last, groups.get(i + 1).first - 1);
                    }
                    Weaver.Woven woven;
                    try {
                        woven = Weaver.weave(g.files.get("vsh"), g.files.get("fsh"),
                            CoreTargets.shape(target, g.name, g.first, g.files.get("vsh"), g.files.get("fsh")), forTarget);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalStateException(target + " " + g.firstRelease + ": " + e.getMessage(), e);
                    }
                    for (Map.Entry<String, String> skip : woven.skipped().entrySet()) {
                        System.out.println("WARNING: " + skip.getKey() + " left out of " + target + " " + g.firstRelease + ": " + skip.getValue());
                        warnings++;
                    }
                    Map<String, String> files = new LinkedHashMap<>();
                    files.put(g.name + ".vsh", woven.vertex());
                    files.put(g.name + ".fsh", woven.fragment());
                    if (g.files.get("json") != null) {
                        files.put(g.name + ".json", programJson(g.files.get("json"), g.first, target));
                    }
                    // a program of an older shader name that this release still has, its JSON pointing at the
                    // shader woven here (1.21.2 to 1.21.4): its own copy, so the base folder's older one
                    // never reaches it, with the uniforms the weaving reads
                    for (Map.Entry<String, String> e : g.files.entrySet()) {
                        if (e.getKey().startsWith("program:") && e.getValue() != null) {
                            files.put(e.getKey().substring("program:".length()), programJson(e.getValue(), g.first, target));
                        }
                    }
                    boolean isBase = i == 0 && base;
                    String directory = isBase ? null : PREFIX + label + "_" + g.firstRelease.replace('.', '_');
                    Path core = (isBase ? output : output.resolve(directory)).resolve("assets/minecraft/shaders/core");
                    Files.createDirectories(core);
                    for (Map.Entry<String, String> f : files.entrySet()) {
                        Files.writeString(core.resolve(f.getKey()), f.getValue(), StandardCharsets.UTF_8);
                    }
                    String span = target + " " + g.firstRelease + ", formats " + g.first + "-" + g.last + ": " + woven.applied();
                    if (isBase) {
                        System.out.println("base folder: " + span);
                    } else {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("formats", Map.of("min_inclusive", (double) g.first, "max_inclusive", (double) g.last));
                        entry.put("min_format", (double) g.first);
                        entry.put("max_format", (double) g.last);
                        entry.put("directory", directory);
                        entries.add(entry);
                        System.out.println("overlay " + directory + ": " + span);
                    }
                }
            }
        }
        writeOverlays(output.resolve("pack.mcmeta"), pack.resolve("pack.mcmeta"), entries);
        return warnings;
    }

    /** Consecutive releases whose shader for the target is the same, woven the same way. */
    private static final class Group {
        String name;
        String firstRelease;
        int first;
        int last;
        Map<String, String> files;
        String key;
    }

    private static List<Group> groups(Minecraft minecraft, List<Minecraft.Release> releases, String target, List<String> chain, Path pack) throws IOException {
        List<Group> groups = new ArrayList<>();
        List<Map<String, Object>> overlays = packOverlays(pack);
        for (Minecraft.Release r : releases) {
            Minecraft.Shaders shaders = minecraft.shaders(r);
            String name = null;
            for (String candidate : chain) {
                if (shaders.names().contains(candidate + ".vsh")) {
                    name = candidate;
                    break;
                }
            }
            if (name == null) {
                continue;
            }
            Map<String, String> files = new LinkedHashMap<>();
            int releaseFormat = shaders.format();
            for (String ext : List.of("vsh", "fsh", "json")) {
                // the pack's own shader where it has one for this release (its effects kept), else Minecraft's
                String own = packShader(pack, overlays, releaseFormat, name + "." + ext);
                files.put(ext, own != null ? own : minecraft.shader(r, name + "." + ext));
            }
            for (String other : chain) {
                if (!other.equals(name) && shaders.names().contains(other + ".json")) {
                    files.put("program:" + other + ".json", minecraft.shader(r, other + ".json"));
                }
            }
            int format = shaders.format();
            // the same text woven the same way: the shape changes at these formats
            String key = name + files + (format >= CoreTargets.MODEL_SPACE_FROM) + (format >= CoreTargets.DRAW_PARAMETERS_FROM)
                + (format >= CoreTargets.GLOBALS_FROM) + CoreTargets.guard(target, name, format);
            Group last = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (last != null && last.key.equals(key)) {
                last.last = Math.max(last.last, format);
                continue;
            }
            Group g = new Group();
            g.name = name;
            g.firstRelease = r.id();
            g.first = format;
            g.last = format;
            g.files = files;
            g.key = key;
            groups.add(g);
        }
        return groups;
    }

    /** The pack's overlay entries, in order, leaving out the ones this compiler wrote. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> packOverlays(Path pack) throws IOException {
        Path meta = pack.resolve("pack.mcmeta");
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.exists(meta)) {
            return out;
        }
        Map<String, Object> parsed = (Map<String, Object>) Json.parse(Files.readString(meta, StandardCharsets.UTF_8).replace("\uFEFF", ""));
        Object overlays = parsed.get("overlays");
        if (overlays instanceof Map<?, ?> o && o.get("entries") instanceof List<?> entries) {
            for (Object e : entries) {
                if (e instanceof Map<?, ?> m && m.get("directory") instanceof String d && !d.startsWith(PREFIX)) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    /** A format bound of an overlay entry: a number, or [major, minor] from 1.21.9. */
    private static int formatOf(Object value) {
        if (value instanceof Double d) {
            return d.intValue();
        }
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Double d) {
            return d.intValue();
        }
        return -1;
    }

    /** Whether an overlay entry applies to a pack format. */
    private static boolean covers(Map<String, Object> entry, int format) {
        Object formats = entry.get("formats");
        int min = -1;
        int max = -1;
        if (formats instanceof Double d) {
            min = max = d.intValue();
        } else if (formats instanceof List<?> list && list.size() == 2) {
            min = formatOf(list.get(0));
            max = formatOf(list.get(1));
        } else if (formats instanceof Map<?, ?> m) {
            min = formatOf(m.get("min_inclusive"));
            max = formatOf(m.get("max_inclusive"));
        } else {
            min = formatOf(entry.get("min_format"));
            max = formatOf(entry.get("max_format"));
        }
        return min >= 0 && min <= format && format <= max;
    }

    /**
     * The pack's own core shader file for a pack format, as the game would read it (the last overlay that
     * covers the format and has the file, else the base folder), or null where it has none. A file this
     * compiler wove before is refused: weaving it again would weave the stitches in twice.
     */
    private static String packShader(Path pack, List<Map<String, Object>> overlays, int format, String file) throws IOException {
        Path found = null;
        for (Map<String, Object> entry : overlays) {
            Path p = pack.resolve((String) entry.get("directory")).resolve("assets/minecraft/shaders/core").resolve(file);
            if (covers(entry, format) && Files.exists(p)) {
                found = p;
            }
        }
        if (found == null) {
            Path p = pack.resolve("assets/minecraft/shaders/core").resolve(file);
            found = Files.exists(p) ? p : null;
        }
        if (found == null) {
            return null;
        }
        String text = Files.readString(found, StandardCharsets.UTF_8);
        if (text.contains(Weaver.WOVEN_MARK)) {
            fail(pack.relativize(found) + " was woven by Stitch already; build the pack's own shaders again first");
        }
        return text;
    }

    /** The program JSON (to 1.21.1) with the uniforms the weaving uses added. */
    @SuppressWarnings("unchecked")
    private static String programJson(String json, int format, String target) {
        Map<String, Object> program = (Map<String, Object>) Json.parse(json);
        List<Object> uniforms = (List<Object>) program.computeIfAbsent("uniforms", k -> new ArrayList<>());
        if (format < CoreTargets.MODEL_SPACE_FROM) {
            addUniform(uniforms, CoreTargets.VIEW_ROTATION_UNIFORM);
        }
        addUniform(uniforms, CoreTargets.GAME_TIME_UNIFORM);
        if (target.equals("text_world")) {
            addUniform(uniforms, CoreTargets.SCREEN_SIZE_UNIFORM);
        }
        return Json.write(program, "    ") + "\n";
    }

    @SuppressWarnings("unchecked")
    private static void addUniform(List<Object> uniforms, Map<String, Object> uniform) {
        if (uniforms.stream().noneMatch(u -> uniform.get("name").equals(((Map<String, Object>) u).get("name")))) {
            uniforms.add(new LinkedHashMap<>(uniform));
        }
    }

    /** pack.mcmeta with the stitch overlay entries replaced by these, the pack's own entries kept. */
    @SuppressWarnings("unchecked")
    private static void writeOverlays(Path out, Path in, List<Map<String, Object>> entries) throws IOException {
        Map<String, Object> meta = (Map<String, Object>) Json.parse(Files.readString(in, StandardCharsets.UTF_8).replace("﻿", ""));
        Map<String, Object> overlays = (Map<String, Object>) meta.computeIfAbsent("overlays", k -> new LinkedHashMap<>());
        List<Object> list = new ArrayList<>((List<Object>) overlays.getOrDefault("entries", new ArrayList<>()));
        list.removeIf(e -> Objects.toString(((Map<String, Object>) e).get("directory"), "").startsWith(PREFIX));
        list.addAll(entries);
        overlays.put("entries", list);
        Files.writeString(out, Json.write(meta, "  ") + "\n", StandardCharsets.UTF_8);
    }

    /** The pack's stitches: assets/&lt;namespace&gt;/stitches/**.json. */
    static List<Stitch> read(Path pack) throws IOException {
        List<Stitch> out = new ArrayList<>();
        Path assets = pack.resolve("assets");
        if (!Files.isDirectory(assets)) {
            return out;
        }
        try (Stream<Path> namespaces = Files.list(assets)) {
            for (Path ns : namespaces.sorted().toList()) {
                Path dir = ns.resolve("stitches");
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(dir)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                        String rel = dir.relativize(f).toString().replace('\\', '/');
                        String id = ns.getFileName() + ":" + rel.substring(0, rel.length() - 5);
                        try {
                            out.add(Stitch.read(id, Files.readString(f, StandardCharsets.UTF_8), location -> {
                                String[] parts = location.split(":", 2);
                                Path p = assets.resolve(parts[0]).resolve(parts[1]);
                                try {
                                    return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
                                } catch (IOException e) {
                                    return null;
                                }
                            }));
                        } catch (StitchException e) {
                            fail(e.getMessage());
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The releases a versions argument names: "1.18.2+", "1.20.5-1.21.4", or a list "1.20.1,1.21.1". */
    static List<Minecraft.Release> select(List<Minecraft.Release> all, String spec) {
        List<String> ids = all.stream().map(Minecraft.Release::id).toList();
        List<Minecraft.Release> out = new ArrayList<>();
        if (spec.endsWith("+")) {
            int from = index(ids, spec.substring(0, spec.length() - 1));
            out.addAll(all.subList(from, all.size()));
        } else if (spec.contains("-") && !spec.contains(",")) {
            String[] r = spec.split("-", 2);
            out.addAll(all.subList(index(ids, r[0]), index(ids, r[1]) + 1));
        } else {
            for (String id : spec.split(",")) {
                out.add(all.get(index(ids, id.trim())));
            }
            out.sort(Comparator.comparingInt(r -> ids.indexOf(r.id())));
        }
        return out;
    }

    private static int index(List<String> ids, String id) {
        int i = ids.indexOf(id);
        if (i < 0) {
            fail("no Minecraft release " + id);
        }
        return i;
    }

    private static void delete(Path p) throws IOException {
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path q : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(q);
            }
        }
    }

    static void fail(String message) {
        System.err.println("stitch: " + message);
        System.exit(1);
    }
}
