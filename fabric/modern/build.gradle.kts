// The Stitch mod for Fabric with Iris on Minecraft 26.x, which ships without obfuscation, so the mod
// compiles against Minecraft's own jar from a local install (the stitch.minecraftJar property, e.g. in
// ~/.gradle/gradle.properties), of the jar's first release. Two jars: build one with
// -Pstitch.fabric=<group> (26.1 when left out). Everything else comes from official Maven repositories,
// pinned.
//
// The 26.1 jar's reader (the "official" variant) also serves Forge from 1.20.6, which runs on Minecraft's
// own names too: the few names it calls are the same back to 1.20.6.
plugins {
    java
}

repositories {
    maven("https://maven.fabricmc.net/") {
        content { includeGroup("net.fabricmc") }
    }
    mavenCentral()
}

/** A jar: the Minecraft releases it runs on, how it reads resource packs, and its reader's package. */
data class Target(val range: String, val resources: String, val variant: String)

val targets = mapOf(
    "26.1" to Target(">=26.1 <26.3", "resources119", "official"),
    // 26.3's resource manager takes its own filter type
    "26.3" to Target(">=26.3", "resources263", "official263"),
)
val jarGroup = providers.gradleProperty("stitch.fabric").getOrElse("26.1")
val target = targets[jarGroup] ?: error("Stitch has no modern Fabric jar $jarGroup; there are ${targets.keys}")

extra["stitch.range"] = target.range
extra["stitch.resources"] = target.resources
extra["stitch.variant"] = target.variant
extra["stitch.jar"] = "stitch-fabric-$jarGroup"
apply(from = "../shared.gradle.kts")

java {
    // Minecraft 26.x's classes are Java 25's, which only a 25 compiler reads
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    // the classes themselves are Java 17's, as everything in the one Stitch jar is: Forge 1.18.2 reads
    // every class in a mod jar as it starts, and no newer
    options.release = 17
}

val minecraftJar = providers.gradleProperty("stitch.minecraftJar")

dependencies {
    compileOnly(files(minecraftJar))
    compileOnly("net.fabricmc:fabric-loader:0.19.5")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
}
