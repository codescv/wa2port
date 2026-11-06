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

// ---- Offline AI pack pipeline (Method 3A): `./gradlew :core:runAiPackTool --args="extract --wa2-root …"`
tasks.register<JavaExec>("runAiPackTool") {
    group = "wa2port"
    description = "Runs the offline AI pack corpus / validate / compile CLI (AiPackTool)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.wa2port.core.ai.AiPackToolKt")
    maxHeapSize = "3g"
    System.getProperty("wa2.root")?.let { systemProperty("wa2.root", it) }
}

// Copies the compiled core jar + runtime deps into `-PaiPackToolDir=…` (default build/ai-pack-tool/lib)
// so the CLI can be run with plain `java -cp 'lib/*'` (no Gradle daemon contention).
tasks.register<Sync>("installAiPackTool") {
    group = "wa2port"
    description = "Installs AiPackTool jars for the standalone run_tool.sh wrapper."
    from(tasks.named("jar"))
    from(configurations.named("runtimeClasspath"))
    into(providers.gradleProperty("aiPackToolDir").orElse(layout.buildDirectory.dir("ai-pack-tool/lib").map { it.asFile.path }))
}
