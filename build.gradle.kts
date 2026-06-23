plugins {
    kotlin("jvm") version "2.2.21"
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