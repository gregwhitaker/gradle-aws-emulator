pluginManagement {
    includeBuild("../..")
    repositories { gradlePluginPortal(); mavenCentral() }
    plugins {
        id("io.micronaut.application") version "4.6.2"
    }
}
rootProject.name = "aws-emulator-micronaut-example"
