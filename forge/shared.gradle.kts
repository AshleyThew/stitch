// What both Forge builds share: the sources in forge/src, the constants generated for a jar, mods.toml
// and pack.mcmeta filled in for its Minecraft versions, and core and the OptiFine weaving bundled
// inside it (neither touches Minecraft, so neither is remapped). The applying build sets, in extra:
// stitch.range (Forge's version range), stitch.packFormats (an IntRange), stitch.resources (the source
// folder that reads resource packs), stitch.viewSpace and stitch.jar (the jar's name, without .jar).

val range = extra["stitch.range"] as String
val packFormats = extra["stitch.packFormats"] as IntRange
val reader = extra["stitch.resources"] as String
val viewSpace = extra["stitch.viewSpace"] as Boolean
val jarName = extra["stitch.jar"] as String

val bundled: Configuration by configurations.creating {
    isTransitive = true
}

dependencies {
    "implementation"(project(":optifine"))
    bundled(project(":optifine"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

val generated = layout.buildDirectory.dir("generated/stitch")
val buildConstants = tasks.register("buildConstants") {
    val out = generated
    val version = project.version.toString()
    inputs.property("version", version)
    inputs.property("viewSpace", viewSpace)
    outputs.dir(out)
    doLast {
        val file = out.get().file("io/github/ashleythew/stitch/forge/Build.java").asFile
        file.parentFile.mkdirs()
        file.writeText("""
            package io.github.ashleythew.stitch.forge;

            /** Generated for the jar: Stitch's version, and the Minecraft versions it is built for. */
            final class Build {
                static final String VERSION = "$version";
                /** Entity vertices are in view space (before 1.20.5). */
                static final boolean VIEW_SPACE = $viewSpace;

                private Build() {
                }
            }
            """.trimIndent() + "\n")
    }
}

the<SourceSetContainer>().named("main") {
    java.srcDir(rootProject.file("forge/src/main/java"))
    java.srcDir(rootProject.file("minecraft/src/$reader/java"))
    java.srcDir(buildConstants)
    resources.srcDir(rootProject.file("forge/src/main/resources"))
}

tasks.named<ProcessResources>("processResources") {
    val properties = mapOf("version" to project.version, "minecraft" to range,
        "pack_format" to packFormats.first, "pack_format_last" to packFormats.last)
    inputs.properties(properties)
    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) {
        expand(properties)
    }
}

tasks.named<Jar>("jar") {
    archiveFileName = "$jarName.jar"
    from({ bundled.filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(bundled)
    manifest.attributes("MixinConfigs" to "stitch-optifine.mixins.json")
}
