plugins {
    id("org.projectcontinuum.feature")
}

group = "org.projectcontinuum.feature.knime.executor"
description = "Continuum KNIME — a feature for running KNIME workflows"
version = property("featureVersion").toString()

// get continuum platform version from root project properties
val continuumPlatformVersion = property("continuumPlatformVersion").toString()

continuum {
    continuumVersion.set(continuumPlatformVersion)
}

dependencies {
    // Jackson Kotlin module
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
}