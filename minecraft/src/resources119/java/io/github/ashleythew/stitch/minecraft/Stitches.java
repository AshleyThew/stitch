package io.github.ashleythew.stitch.minecraft;

import io.github.ashleythew.stitch.core.Stitch;
import io.github.ashleythew.stitch.core.StitchException;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.Resource;
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
import java.util.function.Predicate;

/**
 * The stitches in the enabled resource packs: assets/&lt;namespace&gt;/stitches/**.json. Everything under
 * stitches/ is listed once and found by its id ("namespace:path"), which reads the same from 1.19 to
 * today: the resource id's class has been renamed since (ResourceLocation to Identifier), and its
 * constructor closed, so nothing here names it - not even the filter's parameter.
 */
public final class Stitches {

    private static final Logger LOG = LoggerFactory.getLogger("stitch");

    private Stitches() {
    }

    /** Every id: a filter on Object, so its lambda doesn't name the resource id's class. */
    @SuppressWarnings("rawtypes")
    private static final Predicate EVERYTHING = id -> true;

    /** Every valid stitch, by id; an invalid one is logged and left out. */
    @SuppressWarnings("unchecked")
    public static List<Stitch> read() {
        Map<String, Resource> files = new HashMap<>();
        Map<?, Resource> listed = (Map<?, Resource>) Minecraft.getInstance().getResourceManager().listResources("stitches", EVERYTHING);
        for (Map.Entry<?, Resource> e : listed.entrySet()) {
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
