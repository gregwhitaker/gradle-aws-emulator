/*
 * Copyright 2026 Greg Whitaker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    `maven-publish`
    id("com.gradle.plugin-publish")
}

group = "io.github.gregwhitaker"
version = providers.gradleProperty("pluginVersion").get()

val projectWebsite = "https://github.com/gregwhitaker/gradle-aws-emulator"
// Module build scripts set their descriptions after applying this convention.
val publicationDescription = providers.provider { project.description }

gradlePlugin {
    website.set(projectWebsite)
    vcsUrl.set("$projectWebsite.git")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            description.set(publicationDescription)
            url.set(projectWebsite)
            scm {
                url.set(projectWebsite)
                connection.set("scm:git:$projectWebsite.git")
                developerConnection.set("scm:git:git@github.com:gregwhitaker/gradle-aws-emulator.git")
            }
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
        }
    }
}

tasks.named("publishPlugins") { dependsOn(tasks.named("check")) }
