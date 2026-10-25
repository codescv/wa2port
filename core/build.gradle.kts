plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Real game assets live outside git; tests skip gracefully when absent.
    val defaultWa2Root = listOf(
        rootProject.file("resource/WA2"),
        rootProject.file("../../resource/WA2"),
    ).firstOrNull { it.isDirectory } ?: rootProject.file("resource/WA2")
    systemProperty(
        "wa2.root",
        System.getProperty("wa2.root")
            ?: System.getenv("WA2_ROOT")
            ?: defaultWa2Root.absolutePath,
    )
    maxHeapSize = "3g"
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
