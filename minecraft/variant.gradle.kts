// The resource pack reader a build compiles, moved into a package of its own for the names it is compiled
// against (stitch.variant), so the one Stitch jar can carry every build's reader side by side and pick the
// running game's: io.github.ashleythew.stitch.minecraft.<variant>.Stitches. The applying build sets, in
// extra: stitch.resources (the source folder that reads resource packs) and stitch.variant.

val reader = extra["stitch.resources"] as String
val variant = extra["stitch.variant"] as String

val generated = layout.buildDirectory.dir("generated/minecraft")
val minecraftSources = tasks.register<Sync>("minecraftSources") {
    inputs.property("variant", variant)
    // the whole folder is synced, so another build's variant left from an earlier run is cleared
    into(generated)
    from(rootProject.file("minecraft/src/$reader/java/io/github/ashleythew/stitch/minecraft")) {
        into("io/github/ashleythew/stitch/minecraft/$variant")
    }
    filter { line ->
        line.replace("package io.github.ashleythew.stitch.minecraft;", "package io.github.ashleythew.stitch.minecraft.$variant;")
    }
}

the<SourceSetContainer>().named("main") {
    // Readers, which picks between them, names nothing of Minecraft's and stays where it is
    java.srcDir(rootProject.file("minecraft/src/main/java"))
    java.srcDir(files(generated).builtBy(minecraftSources))
}
