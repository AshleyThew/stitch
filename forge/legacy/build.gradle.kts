// The Stitch mod for Forge before 1.20.6, which ran on Minecraft remapped to SRG names. Two jars cover
// 1.18.2 to 1.20.4: build one with -Pstitch.forge=<group> (1.19 when left out). NeoForged's ModDevGradle
// compiles against Minecraft's official names and remaps the jar to SRG.
plugins {
    id("net.neoforged.moddev.legacyforge") version "2.0.148"
}

/**
 * A jar: the Forge build it compiles against, the Minecraft versions it runs on and their resource pack
 * formats (Forge wants a pack.mcmeta in every mod), and how it reads resource packs. The SRG names it
 * calls are the same across its range.
 */
data class Target(val forge: String, val range: String, val packFormats: IntRange, val resources: String)

val targets = mapOf(
    "1.18" to Target("1.18.2-40.3.0", "[1.18.2,1.19)", 8..8, "resources118"),
    "1.19" to Target("1.20.1-47.4.10", "[1.19,1.20.5)", 9..22, "resources119"),
)
val jarGroup = providers.gradleProperty("stitch.forge").getOrElse("1.19")
val target = targets[jarGroup] ?: error("Stitch has no legacy Forge jar $jarGroup; there are ${targets.keys}")

extra["stitch.range"] = target.range
extra["stitch.packFormats"] = target.packFormats
extra["stitch.resources"] = target.resources
extra["stitch.variant"] = "srg" + jarGroup.replace(".", "")
extra["stitch.jar"] = "stitch-forge-$jarGroup-dev"
apply(from = "../shared.gradle.kts")

java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

legacyForge {
    // stitch.forgeVersion compiles against another Forge build, to check the jar fits the whole range
    version = providers.gradleProperty("stitch.forgeVersion").getOrElse(target.forge)
}

tasks.named<Jar>("reobfJar") {
    archiveFileName = "stitch-forge-$jarGroup.jar"
}
