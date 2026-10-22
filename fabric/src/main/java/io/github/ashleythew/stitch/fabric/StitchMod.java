package io.github.ashleythew.stitch.fabric;

import io.github.ashleythew.stitch.iris.IrisShape;
import io.github.ashleythew.stitch.iris.IrisWeaving;
import io.github.ashleythew.stitch.minecraft.Readers;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

/** Stitch on Fabric: weaves resource packs' stitches into Iris's shader pack programs. */
public final class StitchMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        FabricLoader loader = FabricLoader.getInstance();
        IrisShape.viewSpace(before(loader, "1.20.5"));
        IrisWeaving.stitches(Readers.of(variant(loader)));
        IrisWeaving.LOG.info("Stitch {}: {}", loader.getModContainer("stitch").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"),
            loader.isModLoaded("iris") ? "weaving stitches into Iris's shader pack programs" : "Iris isn't installed, so there is nothing to weave into");
    }

    /**
     * The resource pack reader for the running game. Before 26.1 Fabric runs Minecraft under its
     * intermediary names, and 1.19 changed how the resource manager lists files; 26.x runs Minecraft's
     * own names, and 26.3 changed the listing's filter.
     */
    private static String variant(FabricLoader loader) {
        if (before(loader, "1.19")) {
            return "intermediary118";
        }
        if (before(loader, "26.1")) {
            return "intermediary119";
        }
        return before(loader, "26.3") ? "official" : "official263";
    }

    /**
     * The running Minecraft is older than this version. Until 1.20.5 Minecraft puts the camera's rotation
     * into each entity vertex.
     */
    private static boolean before(FabricLoader loader, String version) {
        try {
            Version running = loader.getModContainer("minecraft").orElseThrow().getMetadata().getVersion();
            return running.compareTo(Version.parse(version)) < 0;
        } catch (VersionParsingException e) {
            throw new IllegalStateException(e);
        }
    }
}
