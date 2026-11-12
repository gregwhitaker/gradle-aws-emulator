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
