package io.github.ashleythew.stitch.minecraft;

import io.github.ashleythew.stitch.core.Stitch;
import io.github.ashleythew.stitch.core.StitchException;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
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
 * The stitches in the enabled resource packs: assets/&lt;namespace&gt;/stitches/**.json. 1.18's resource
 * manager lists ids, each opened on its own.
 */
public final class Stitches {

    private static final Logger LOG = LoggerFactory.getLogger("stitch");

    private Stitches() {
    }

    /** Every valid stitch, by id; an invalid one is logged and left out. */
    public static List<Stitch> read() {
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        Map<String, ResourceLocation> files = new HashMap<>();
        for (ResourceLocation location : resources.listResources("stitches", path -> true)) {
            files.put(location.toString(), location);
        }
        Map<String, ResourceLocation> definitions = new TreeMap<>();
        files.forEach((id, location) -> {
            if (id.endsWith(".json")) {
                definitions.put(id, location);
            }
        });
        List<Stitch> out = new ArrayList<>();
        for (Map.Entry<String, ResourceLocation> e : definitions.entrySet()) {
            String[] parts = e.getKey().split(":", 2);
            String id = parts[0] + ":" + parts[1].substring("stitches/".length(), parts[1].length() - ".json".length());
            try {
                out.add(Stitch.read(id, text(resources, e.getValue()), location -> {
                    ResourceLocation r = files.get(location);
                    try {
                        return r == null ? null : text(resources, r);
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

    private static String text(ResourceManager resources, ResourceLocation location) throws IOException {
        try (Resource resource = resources.getResource(location); InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
