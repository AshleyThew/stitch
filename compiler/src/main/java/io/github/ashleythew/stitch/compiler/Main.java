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
        List<String> targets = stitches.stream().flatMap(s -> s.targets().stream()).distinct().sorted().toList();
        for (String target : targets) {
            if (!CoreTargets.supported(target)) {
                System.out.println("WARNING: target " + target + " isn't compiled by this version of the compiler yet");
                warnings++;
                continue;
            }
            List<Stitch> forTarget = Weaver.forTarget(stitches, target);
            List<Group> groups = groups(minecraft, releases, target);
            for (int i = 0; i < groups.size(); i++) {
                Group g = groups.get(i);
                if (i + 1 < groups.size()) {
                    g.last = Math.max(g.last, groups.get(i + 1).first - 1);
                }
                Weaver.Woven woven;
                try {
                    woven = Weaver.weave(g.files.get("vsh"), g.files.get("fsh"), CoreTargets.shape(g.first, g.files.get("vsh")), forTarget);
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
                    files.put(g.name + ".json", programJson(g.files.get("json"), g.first));
                }
                // a program of an older shader name that this release still has, its JSON pointing at the
                // shader woven here (1.21.2 to 1.21.4): its own copy, so the base folder's older one
                // never reaches it, with the uniforms the weaving reads
                for (Map.Entry<String, String> e : g.files.entrySet()) {
                    if (e.getKey().startsWith("program:") && e.getValue() != null) {
                        files.put(e.getKey().substring("program:".length()), programJson(e.getValue(), g.first));
                    }
                }
                boolean isBase = i == 0 && base;
                String directory = isBase ? null : PREFIX + target + "_" + g.firstRelease.replace('.', '_');
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

    private static List<Group> groups(Minecraft minecraft, List<Minecraft.Release> releases, String target) throws IOException {
        List<Group> groups = new ArrayList<>();
        for (Minecraft.Release r : releases) {
            Minecraft.Shaders shaders = minecraft.shaders(r);
            String name = null;
            for (String candidate : CoreTargets.SHADERS.get(target)) {
                if (shaders.names().contains(candidate + ".vsh")) {
                    name = candidate;
                    break;
                }
            }
            if (name == null) {
                continue;
            }
            Map<String, String> files = new LinkedHashMap<>();
            files.put("vsh", minecraft.shader(r, name + ".vsh"));
            files.put("fsh", minecraft.shader(r, name + ".fsh"));
            files.put("json", minecraft.shader(r, name + ".json"));
            for (String other : CoreTargets.SHADERS.get(target)) {
                if (!other.equals(name) && shaders.names().contains(other + ".json")) {
                    files.put("program:" + other + ".json", minecraft.shader(r, other + ".json"));
                }
            }
            int format = shaders.format();
            // the same text woven the same way: the shape changes at these formats
            String key = name + files + (format >= CoreTargets.MODEL_SPACE_FROM) + (format >= CoreTargets.DRAW_PARAMETERS_FROM)
                + (format >= CoreTargets.GLOBALS_FROM);
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

    /** The program JSON (to 1.21.1) with the uniforms the weaving uses added. */
    @SuppressWarnings("unchecked")
    private static String programJson(String json, int format) {
        Map<String, Object> program = (Map<String, Object>) Json.parse(json);
        List<Object> uniforms = (List<Object>) program.computeIfAbsent("uniforms", k -> new ArrayList<>());
        if (format < CoreTargets.MODEL_SPACE_FROM) {
            addUniform(uniforms, CoreTargets.VIEW_ROTATION_UNIFORM);
        }
        addUniform(uniforms, CoreTargets.GAME_TIME_UNIFORM);
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
