// Stitch inside OptiFine's shader programs. OptiFine's shader loader is the same on every Minecraft
// version it supports, and this module touches nothing of Minecraft's: each loader's mod supplies the
// stitches (read from its version's resource manager) and bundles this module with its mixin.
plugins {
    `java-library`
}

repositories {
    maven("https://maven.fabricmc.net/") {
        content { includeGroup("net.fabricmc") }
    }
    mavenCentral()
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    api(project(":core"))
    // only Mixin's annotations, which every loader's Mixin shares
    compileOnly("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    // the driver's extension list, from the game's own LWJGL
    compileOnly("org.lwjgl:lwjgl:3.3.3")
    compileOnly("org.lwjgl:lwjgl-opengl:3.3.3")
}
