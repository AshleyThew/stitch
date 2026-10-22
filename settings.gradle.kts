pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            content {
                includeGroupByRegex("net\\.fabricmc.*")
                includeGroup("fabric-loom")
            }
        }
        gradlePluginPortal()
    }
}

rootProject.name = "stitch"

include("core", "compiler", "optifine", "iris", "fabric:legacy", "fabric:modern", "forge:legacy", "mod")
