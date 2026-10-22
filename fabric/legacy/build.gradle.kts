// The Stitch mod for Fabric with Iris before 26.x, which ran on Minecraft remapped to Fabric's
// intermediary names. Two jars cover 1.18.2 to 1.21.11: build one with -Pstitch.fabric=<group> (1.19
// when left out). Loom compiles against Minecraft's official names and remaps the jar to intermediary,
// whose names stay the same from release to release.
plugins {
    id("fabric-loom") version "1.17.21"
}

/**
 * A jar: the Minecraft release it compiles against, the releases it runs on, how it reads resource
 * packs, and the Java those releases run on.
 */
data class Target(val minecraft: String, val range: String, val resources: String, val java: Int)

val targets = mapOf(
    "1.18" to Target("1.18.2", "~1.18.2", "resources118", 17),
    "1.19" to Target("1.20.1", ">=1.19 <26", "resources119", 17),
)
val jarGroup = providers.gradleProperty("stitch.fabric").getOrElse("1.19")
val target = targets[jarGroup] ?: error("Stitch has no legacy Fabric jar $jarGroup; there are ${targets.keys}")

extra["stitch.range"] = target.range
extra["stitch.resources"] = target.resources
extra["stitch.variant"] = "intermediary" + jarGroup.replace(".", "")
extra["stitch.jar"] = "stitch-fabric-$jarGroup-dev"
apply(from = "../shared.gradle.kts")

// stitch.java compiles for a newer Java, whose class files releases past the range's start need to be
// read (with stitch.minecraftVersion, to check the jar fits; never for a jar to ship)
val javaVersion = providers.gradleProperty("stitch.java").map(String::toInt).getOrElse(target.java)

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaVersion
}

dependencies {
    // stitch.minecraftVersion compiles against another release, to check the jar fits the whole range
    "minecraft"("com.mojang:minecraft:" + providers.gradleProperty("stitch.minecraftVersion").getOrElse(target.minecraft))
    "mappings"(loom.officialMojangMappings())
    "modCompileOnly"("net.fabricmc:fabric-loader:0.19.5")
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName = "stitch-fabric-$jarGroup.jar"
}
