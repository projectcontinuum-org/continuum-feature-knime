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

    // JSON schema validation (required by ValidationHelper in continuum-commons)
    implementation("com.networknt:json-schema-validator:3.0.0")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}