plugins {
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass = "io.github.ashleythew.stitch.compiler.Main"
}

// one runnable jar, the core inside: what the GitHub Action downloads
tasks.jar {
    archiveFileName = "stitch-compiler.jar"
    manifest {
        attributes("Main-Class" to "io.github.ashleythew.stitch.compiler.Main")
    }
    from(configurations.runtimeClasspath.map { classpath -> classpath.filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
}
