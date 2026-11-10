plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// Emit Java 17 bytecode (Android-compatible) using whatever JDK runs Gradle; no specific JDK required.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api("org.jsoup:jsoup:1.24.1")
    // jsoup's nullability annotations; Kotlin needs them on the classpath to infer types from jsoup calls.
    api("org.jspecify:jspecify:1.0.0")
    api("com.squareup.okhttp3:okhttp:5.5.1")
    // Android ships org.json in the framework; the app module excludes this.
    implementation("org.json:json:20260814")
    testImplementation(kotlin("test"))
}

application {
    // ./gradlew :core:run --args="<story url> [output dir]"
    mainClass.set("pocketfare.core.CliKt")
}

tasks.test { useJUnitPlatform() }

tasks.named<JavaExec>("run") { workingDir = rootDir }
tasks.test { systemProperty("fixtures.dir", rootDir.resolve("fixtures").absolutePath) }
