plugins {
    id("org.projectcontinuum.worker")
}

group = "org.projectcontinuum.feature.knime"
description = "Continuum Feature KNIME Worker — Spring Boot worker application for KNIME feature nodes"
version = property("featureVersion").toString()

// get continuum platform version from root project properties
val continuumPlatformVersion = property("continuumPlatformVersion").toString()

// knime-lts-full + dedicated Temurin 21 JRE for the worker — built from base-image/Dockerfile and
// published under this tag by .github/workflows/build-base-image.yml. Must be pushed before :worker:jib.
val baseImageVersion = rootProject.file("base-image/VERSION").readText().trim()

continuum {
    continuumVersion.set(continuumPlatformVersion)
    jibFromImage.set("projectcontinuum/continuum-feature-knime-base:$baseImageVersion")
    jibToImage.set("projectcontinuum/continuum-feature-knime:${project.version}")
}

dependencies {
    // Feature node modules (local project)
    implementation(project(":features:continuum-feature-knime-executor"))
}