plugins {
    kotlin("jvm")
    `java-library`
}
kotlin { jvmToolchain(21) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
// Build-time FCC conversion; see tools/update-mw-db.sh. Not part of the normal build.
tasks.register<JavaExec>("importMwStations") {
    description = "Converts FCC AM Query list output into the MW station table"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.kiwidroid.core.FccAmImportKt")
    workingDir = rootDir
}
// Build-time Wikipedia format import (network); see tools/update-mw-db.sh --formats.
tasks.register<JavaExec>("importMwFormats") {
    description = "Fetches MW station programming formats from Wikidata/Wikipedia"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.kiwidroid.core.MwFormatImport")
    workingDir = rootDir
}
