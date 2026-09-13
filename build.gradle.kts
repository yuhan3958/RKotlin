plugins {
    kotlin("jvm") version "2.4.10"
    id("edu.sc.seis.launch4j") version "4.0.0"
}

group = "me.rkt"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

sourceSets.main {
    resources.srcDir("src/main/rk")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { file ->
        if (file.isDirectory) file else zipTree(file)
    })
    manifest {
        attributes["Main-Class"] = "me.rkt.MainKt"
    }
}

launch4j {
    mainClassName.set("me.rkt.MainKt")
    outfile.set("RktC.exe")
    outputDir.set("dist")
    jreMinVersion.set("21")
}
