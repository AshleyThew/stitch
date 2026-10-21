// Stitch inside Iris's shader programs, for every Iris from 1.6 (Minecraft 1.18.2) on. Nothing here
// compiles against Iris or Minecraft: each loader's mod supplies the stitches and bundles this module
// with its mixins, which find Iris's classes by name.
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
    // only Mixin's annotations and plugin interface (with the ASM it brings), which every loader's Mixin shares
    compileOnly("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
}
