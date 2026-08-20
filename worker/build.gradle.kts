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
}

dependencies {
    // Feature node modules (local project)
    implementation(project(":features:continuum-feature-knime-executor"))
}
