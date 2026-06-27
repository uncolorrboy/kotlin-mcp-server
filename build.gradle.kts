plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
}

group = "ru.sapozhnikov"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.0"
val mcpVersion = "0.13.0"

dependencies {
    implementation("io.ktor:ktor-client-cio:${ktorVersion}")
    implementation("io.ktor:ktor-server-cio:${ktorVersion}")
    implementation("io.modelcontextprotocol:kotlin-sdk-server:${mcpVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("ru.sapozhnikov.MainKt")
}

kotlin {
    jvmToolchain(19)
}

tasks.test {
    useJUnitPlatform()
}