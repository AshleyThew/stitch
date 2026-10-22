// The one Stitch jar, for Fabric with Iris and Forge with OptiFine on every Minecraft release: the builds
// of fabric/ and forge/ merged. Each build's resource pack reader sits in a package of its own (see
// minecraft/variant.gradle.kts), and the loader's entry point picks the running game's; everything else
// (core, the Iris and OptiFine weaving, the entry points) is the same in every build and taken once.
// Fabric reads fabric.mod.json and Forge META-INF/mods.toml, and neither looks at the other's.
//
// Build the parts first (the release workflow does), then ./gradlew :mod:jar:
//   ./gradlew :fabric:legacy:remapJar -Pstitch.fabric=1.18     (and 1.19)
//   ./gradlew :forge:legacy:reobfJar -Pstitch.forge=1.18       (and 1.19)
//   ./gradlew :fabric:modern:jar -Pstitch.fabric=26.1 -Pstitch.minecraftJar=<26.1 client jar>
//   ./gradlew :fabric:modern:jar -Pstitch.fabric=26.3 -Pstitch.minecraftJar=<26.3 client jar>
plugins {
    base
}

/** Each part, and the reader it brings: the names it calls Minecraft by, and the resource API. */
val parts = linkedMapOf(
    // first, so the classes every part shares come from a Java 17 build
    "fabric/legacy/build/libs/stitch-fabric-1.18.jar" to "intermediary118",
    "fabric/legacy/build/libs/stitch-fabric-1.19.jar" to "intermediary119",
    "forge/legacy/build/libs/stitch-forge-1.18.jar" to "srg118",
    "forge/legacy/build/libs/stitch-forge-1.19.jar" to "srg119",
    // Fabric 26.1 and 26.2, and Forge from 1.20.6: Minecraft's own names
    "fabric/modern/build/libs/stitch-fabric-26.1.jar" to "official",
    "fabric/modern/build/libs/stitch-fabric-26.3.jar" to "official263",
)

// Forge 1.18.2 reads every class in a mod jar as it starts, and nothing newer than Java 17's
val newestClassFile = 61

val metadata = tasks.register<Copy>("metadata") {
    val fabric = mapOf("version" to project.version.toString(), "minecraft" to ">=1.18.2")
    // Forge with OptiFine runs to 26.2; 1.18.2's resource pack format up to 26.2's
    val forge = mapOf("version" to project.version.toString(), "minecraft" to "[1.18.2,26.3)", "pack_format" to 8, "pack_format_last" to 88)
    inputs.property("fabric", fabric.toString())
    inputs.property("forge", forge.toString())
    from(rootProject.file("fabric/src/main/resources/fabric.mod.json")) {
        expand(fabric)
    }
    from(rootProject.file("forge/src/main/resources")) {
        expand(forge)
    }
    into(layout.buildDirectory.dir("metadata"))
}

tasks.register<Jar>("jar") {
    group = "build"
    description = "The one Stitch jar, from the parts' jars"
    archiveFileName = "stitch.jar"
    destinationDirectory = layout.buildDirectory.dir("libs")
    val files = parts.keys.map { rootProject.file(it) }
    inputs.files(files)
    doFirst {
        val missing = files.filterNot { it.exists() }
        check(missing.isEmpty()) { "Build Stitch's parts first (see mod/build.gradle.kts); missing: ${missing.joinToString()}" }
    }
    from(metadata)
    files.forEach { part ->
        from({ zipTree(part) }) {
            exclude("fabric.mod.json", "META-INF/**", "pack.mcmeta")
        }
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes("MixinConfigs" to "stitch-optifine.mixins.json")

    doLast {
        val jar = archiveFile.get().asFile
        val tree = zipTree(jar)
        parts.values.forEach { variant ->
            check(!tree.matching { include("io/github/ashleythew/stitch/minecraft/$variant/Stitches.class") }.isEmpty) {
                "stitch.jar has no $variant reader"
            }
        }
        tree.matching { include("**/*.class") }.visit {
            if (!isDirectory) {
                val bytes = file.readBytes()
                val major = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
                check(major <= newestClassFile) { "$relativePath is a Java ${major - 44} class; stitch.jar takes Java 17's at most" }
            }
        }
    }
}

tasks.named("assemble") {
    dependsOn("jar")
}
