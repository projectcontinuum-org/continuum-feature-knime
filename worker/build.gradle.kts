plugins {
    id("org.projectcontinuum.worker")
}

group = "org.projectcontinuum.feature.knime"
description = "Continuum Feature KNIME Worker — Spring Boot worker application for KNIME feature nodes"
version = property("featureVersion").toString()

// get continuum platform version from root project properties
val continuumPlatformVersion = property("continuumPlatformVersion").toString()

continuum {
    continuumVersion.set(continuumPlatformVersion)
    jibFromImage.set("knime/knime-full:r-5.5.3-414")
    jibToImage.set("projectcontinuum/continuum-feature-knime:${project.version}")
}

dependencies {
    // Feature node modules (local project)
    implementation(project(":features:continuum-feature-knime-executor"))
}