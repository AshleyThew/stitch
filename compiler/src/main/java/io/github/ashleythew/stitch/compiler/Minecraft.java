package io.github.ashleythew.stitch.compiler;

import io.github.ashleythew.stitch.core.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Minecraft's releases and their core shaders, from Mojang's own servers (the version manifest and each
 * release's client jar), cached on disk: a release's shaders never change.
 */
final class Minecraft {

    static final String USER_AGENT = "stitch-compiler";
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String CORE = "assets/minecraft/shaders/core/";

    record Release(String id, String url) {
    }

    private final Path cache;
    private List<Release> releases;

    Minecraft(Path cache) {
        this.cache = cache;
    }

    /** Every release, oldest first. */
    List<Release> releases() throws IOException {
        if (releases == null) {
            Map<?, ?> manifest = (Map<?, ?>) Json.parse(text(MANIFEST));
            List<Release> out = new ArrayList<>();
            for (Object o : (List<?>) manifest.get("versions")) {
                Map<?, ?> v = (Map<?, ?>) o;
                if ("release".equals(v.get("type"))) {
                    out.add(new Release((String) v.get("id"), (String) v.get("url")));
                }
            }
            Collections.reverse(out);
            releases = out;
        }
        return releases;
    }

    /** The release's resource pack format, and the names of its core shader files. */
    record Shaders(int format, TreeSet<String> names) {
    }

    Shaders shaders(Release release) throws IOException {
        Path dir = cache.resolve(release.id());
        Path index = dir.resolve("index.txt");
        if (!Files.exists(index)) {
            RemoteZip jar = jar(release);
            byte[] versionJson = jar.read("version.json");
            int format = format(new String(versionJson, StandardCharsets.UTF_8));
            List<String> lines = new ArrayList<>();
            lines.add(String.valueOf(format));
            for (String name : jar.entries.keySet()) {
                if (name.startsWith(CORE) && !name.endsWith("/")) {
                    lines.add(name.substring(CORE.length()));
                }
            }
            Files.createDirectories(dir);
            Path temporary = dir.resolve("index.txt.part");
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            Files.move(temporary, index, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        return new Shaders(Integer.parseInt(lines.get(0)), new TreeSet<>(lines.subList(1, lines.size())));
    }

    /** A core shader file's text (name.vsh, name.fsh, name.json), or null if the release has none. */
    String shader(Release release, String file) throws IOException {
        if (!shaders(release).names().contains(file)) {
            return null;
        }
        Path path = cache.resolve(release.id()).resolve("core").resolve(file);
        if (!Files.exists(path)) {
            byte[] bytes = jar(release).read(CORE + file);
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(file + ".part");
            Files.write(temporary, bytes);
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private final java.util.HashMap<String, RemoteZip> jars = new java.util.HashMap<>();

    private RemoteZip jar(Release release) throws IOException {
        RemoteZip jar = jars.get(release.id());
        if (jar == null) {
            Map<?, ?> version = (Map<?, ?>) Json.parse(text(release.url()));
            Map<?, ?> client = (Map<?, ?>) ((Map<?, ?>) version.get("downloads")).get("client");
            jar = new RemoteZip((String) client.get("url"), ((Double) client.get("size")).longValue());
            jars.put(release.id(), jar);
        }
        return jar;
    }

    /** The resource pack format in a client jar's version.json: a number, or resource_major from 1.21.9. */
    static int format(String versionJson) {
        Object pack = ((Map<?, ?>) Json.parse(versionJson)).get("pack_version");
        if (pack instanceof Double d) {
            return d.intValue();
        }
        Map<?, ?> m = (Map<?, ?>) pack;
        Object v = m.containsKey("resource_major") ? m.get("resource_major") : m.get("resource");
        return ((Double) v).intValue();
    }

    private static String text(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setConnectTimeout(30_000);
        c.setReadTimeout(60_000);
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
