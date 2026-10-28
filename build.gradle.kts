plugins {
    application
}

group = "dev.racegame"
version = "0.1.0"

repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        content {
            includeModule("net.minestom", "minestom")
        }
    }
    mavenCentral()
}

dependencies {
    implementation("net.minestom:minestom:26_3-SNAPSHOT")
    runtimeOnly("ch.qos.logback:logback-classic:1.6.5")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("dev.racegame.RaceGameServer")
}

val resourcePackZip = tasks.register<Zip>("resourcePackZip") {
    from(layout.projectDirectory.dir("resource-pack"))
    archiveFileName.set("chroma-rift-pack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/resource-pack"))
}

tasks.processResources {
    dependsOn(resourcePackZip)
    from(resourcePackZip.map { it.archiveFile })
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}
