import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.0"
}

group = "org.projectcontinuum.feature.knime"
description = "Installs the extensions listed in a PDE .target file into a KNIME installation via the p2 director"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

// Runs on KNIME's bundled JRE, which is 17 on KNIME 5.5 and 21 on 5.12 — target the lowest.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
}

// Self-contained jar (Kotlin stdlib bundled) so it runs with a plain `java -jar`.
tasks.jar {
    archiveFileName.set("knime-target-installer.jar")
    manifest {
        attributes("Main-Class" to "org.projectcontinuum.feature.knime.targetinstaller.MainKt")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } })
}
