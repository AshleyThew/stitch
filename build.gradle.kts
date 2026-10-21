allprojects {
    group = "io.github.ashleythew.stitch"
    // a release's version comes from its tag (the release workflow); a local build is a snapshot
    version = providers.gradleProperty("stitch.version").getOrElse("0.1.0-SNAPSHOT")
}
