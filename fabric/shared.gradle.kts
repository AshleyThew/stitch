// What both Fabric builds share: the sources in fabric/src, fabric.mod.json filled in for the jar's
// Minecraft versions, and core and the Iris weaving bundled inside it (neither touches Minecraft, so
// neither is remapped). The applying build sets, in extra: stitch.range (fabric.mod.json's Minecraft
// version range), stitch.resources (the source folder that reads resource packs) and stitch.jar (the
// jar's name, without .jar).

val range = extra["stitch.range"] as String
val reader = extra["stitch.resources"] as String
val jarName = extra["stitch.jar"] as String

val bundled: Configuration by configurations.creating {
    isTransitive = true
}

dependencies {
    "implementation"(project(":iris"))
    bundled(project(":iris"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

the<SourceSetContainer>().named("main") {
    java.srcDir(rootProject.file("fabric/src/main/java"))
    java.srcDir(rootProject.file("minecraft/src/$reader/java"))
    resources.srcDir(rootProject.file("fabric/src/main/resources"))
}

tasks.named<ProcessResources>("processResources") {
    val properties = mapOf("version" to project.version, "minecraft" to range)
    inputs.properties(properties)
    filesMatching("fabric.mod.json") {
        expand(properties)
    }
}

tasks.named<Jar>("jar") {
    archiveFileName = "$jarName.jar"
    from({ bundled.filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(bundled)
}
