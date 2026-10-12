pluginManagement {
    val continuumPlatformVersion: String by settings
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("org.projectcontinuum.feature") version continuumPlatformVersion
        id("org.projectcontinuum.feature-java") version continuumPlatformVersion
        id("org.projectcontinuum.worker") version continuumPlatformVersion
        kotlin("jvm") version "2.2.0"
        kotlin("plugin.spring") version "2.2.0"
        kotlin("plugin.jpa") version "2.2.0"
    }
}

rootProject.name = "continuum-feature-knime"

include(":features:continuum-feature-knime-executor")
include(":worker")
include(":scheduler")
