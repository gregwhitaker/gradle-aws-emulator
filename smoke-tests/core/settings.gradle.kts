pluginManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "publicationUnderTest"
                    url = uri(file(providers.gradleProperty("pluginRepository").get()))
                }
            }
            filter {
                includeGroup("io.github.gregwhitaker")
                includeGroup("io.github.gregwhitaker.aws-emulator")
                includeGroup("io.github.gregwhitaker.aws-emulator.spring-boot")
            }
        }
        mavenCentral()
    }
    plugins {
        id("io.github.gregwhitaker.aws-emulator") version providers.gradleProperty("pluginVersion").get()
    }
}

rootProject.name = "published-core-consumer"
