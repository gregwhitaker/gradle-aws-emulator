pluginManagement {
    includeBuild("../..")
    repositories { gradlePluginPortal(); mavenCentral() }
    plugins {
        kotlin("jvm") version "2.4.21"
        id("io.ktor.plugin") version providers.gradleProperty("ktorVersion").getOrElse("3.6.1")
    }
}
rootProject.name = "aws-emulator-ktor-example"
