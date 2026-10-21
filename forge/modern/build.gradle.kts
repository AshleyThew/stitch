// The Stitch mod for Forge from 1.20.6, which runs on Minecraft's official names: one jar for every
// version since. It compiles against Minecraft's own jar from a local install (the stitch.minecraftJar
// property, as for the Fabric mod: 26.x ships without obfuscation, and the few names Stitch calls are
// the same back to 1.20.6) and Forge's mod annotation, from Forge's Maven.
plugins {
    java
}

repositories {
    maven("https://maven.minecraftforge.net/") {
        content { includeGroup("net.minecraftforge") }
    }
    mavenCentral()
}

extra["stitch.range"] = "[1.20.6,26.3)"
// 1.20.6's resource pack format up to 26.2's
extra["stitch.packFormats"] = 32..88
extra["stitch.resources"] = "resources119"
extra["stitch.viewSpace"] = false
extra["stitch.jar"] = "stitch-forge-1.20.6"
apply(from = "../shared.gradle.kts")

java {
    // Minecraft 26.x's classes are Java 25's, which only a 25 compiler reads; the mod runs on 1.20.6's 21
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

val minecraftJar = providers.gradleProperty("stitch.minecraftJar")

dependencies {
    compileOnly(files(minecraftJar))
    compileOnly("net.minecraftforge:javafmllanguage:1.20.6-50.2.0") {
        isTransitive = false
    }
    compileOnly("org.slf4j:slf4j-api:2.0.17")
}
