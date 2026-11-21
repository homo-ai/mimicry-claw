import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    application
    `maven-publish`
}

group = "com.mimicry"
version = "1.0.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xcontext-receivers")
        optIn.add("kotlin.ExperimentalStdlibApi")
    }
}

tasks.withType<KotlinCompilationTask<*>> {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

application {
    mainClass = "com.mimicryos.agent.MainKt"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("com.github.ajalt.clikt:clikt:5.0.0")
    implementation("com.github.ajalt.mordant:mordant:3.0.0")
    implementation("org.jline:jline:3.29.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("io.mockk:mockk:1.13.13")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Build a fat JAR with all dependencies
tasks.register<Jar>("fatJar") {
    dependsOn("classes")
    manifest {
        attributes("Main-Class" to "com.mimicryos.agent.MainKt")
    }
    from(sourceSets.main.get().output)
    // Unpack dependency JARs into the fat JAR. Nesting the JARs themselves
    // makes the JVM unable to load their classes (e.g. kotlin/jvm/internal/Intrinsics).
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.EC")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
