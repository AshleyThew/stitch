package io.github.ashleythew.stitch.minecraft;

import io.github.ashleythew.stitch.core.Stitch;
import io.github.ashleythew.stitch.core.StitchException;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The stitches in the enabled resource packs: assets/&lt;namespace&gt;/stitches/**.json. Everything under
 * stitches/ is listed once and found by its id ("namespace:path"). From 26.3 the resource manager takes
 * its own filter type, ResourceManager.Selector, in place of a Predicate.
 */
public final class Stitches {

    private static final Logger LOG = LoggerFactory.getLogger("stitch");

    private Stitches() {
    }

    /** Every valid stitch, by id; an invalid one is logged and left out. */
    public static List<Stitch> read() {
        Map<String, Resource> files = new HashMap<>();
        ResourceManager.Selector everything = id -> true;
        for (Map.Entry<Identifier, Resource> e : Minecraft.getInstance().getResourceManager().listResources("stitches", everything).entrySet()) {
            files.put(e.getKey().toString(), e.getValue());
        }
        Map<String, Resource> definitions = new TreeMap<>();
        files.forEach((id, resource) -> {
            if (id.endsWith(".json")) {
                definitions.put(id, resource);
            }
        });
        List<Stitch> out = new ArrayList<>();
        for (Map.Entry<String, Resource> e : definitions.entrySet()) {
            String[] parts = e.getKey().split(":", 2);
            String id = parts[0] + ":" + parts[1].substring("stitches/".length(), parts[1].length() - ".json".length());
            try {
                out.add(Stitch.read(id, text(e.getValue()), location -> {
                    Resource r = files.get(location);
                    try {
                        return r == null ? null : text(r);
                    } catch (IOException ex) {
                        return null;
                    }
                }));
            } catch (StitchException | IOException ex) {
                LOG.warn("stitch {} left out: {}", id, ex.getMessage());
            }
        }
        return out;
    }

    private static String text(Resource resource) throws IOException {
        try (InputStream in = resource.open()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
