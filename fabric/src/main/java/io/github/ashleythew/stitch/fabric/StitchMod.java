package io.github.ashleythew.stitch.fabric;

import io.github.ashleythew.stitch.iris.IrisShape;
import io.github.ashleythew.stitch.iris.IrisWeaving;
import io.github.ashleythew.stitch.minecraft.Stitches;
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
        IrisWeaving.stitches(Stitches::read);
        IrisWeaving.LOG.info("Stitch {}: {}", loader.getModContainer("stitch").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"),
            loader.isModLoaded("iris") ? "weaving stitches into Iris's shader pack programs" : "Iris isn't installed, so there is nothing to weave into");
    }

    /**
     * The running Minecraft is older than this version. Until 1.20.5 Minecraft puts the camera's rotation
     * into each entity vertex, which one jar covers on both sides of.
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
