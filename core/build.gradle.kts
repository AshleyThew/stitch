plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

java {
    // the compiler runs wherever Java 17 does, and Minecraft from 1.18 needs 17 anyway
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
}
