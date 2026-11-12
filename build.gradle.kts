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
    base
    id("com.adarshr.test-logger") version "4.0.0" apply false
    id("net.minecraftforge.licenser") version "1.2.0"
}

description = "Build, verification, and documentation for the AWS Emulator Gradle plugin"

license {
    header(rootProject.file("config/license-header.txt"))
    lineEnding.set("\n")
    style.put("py", "HASH")
    style.put("mjs", "BLOCK_COMMENT")
    style.put("svg", "XML")

    // One repository-wide task also covers the independent example and smoke builds.
    tasks.register("repository") {
        files.from(fileTree(rootDir) {
            include(
                "**/*.java", "**/*.kt", "**/*.kts", "**/*.css", "**/*.js", "**/*.mjs",
                "**/*.py", "**/*.sh", "**/*.properties", "**/*.xml", "**/*.html", "**/*.svg",
                "**/*.yml", "**/*.yaml"
            )
            exclude(
                "**/build/**", "**/.gradle/**", "**/.aws-emulator/**", "**/.idea/**",
                "**/node_modules/**", "**/fixtures/**", "gradle/wrapper/**"
            )
        })
    }
}

tasks.named("assemble") { dependsOn(":plugin:assemble", ":spring-boot-plugin:assemble", ":docs:assemble") }
tasks.named("check") { dependsOn(":plugin:check", ":spring-boot-plugin:check", ":docs:check") }
tasks.named("clean") { dependsOn(":plugin:clean", ":spring-boot-plugin:clean", ":docs:clean") }

// Preserve the 0.1 command while keeping real-emulator tests in their own source set.
tasks.register("dockerTest") {
    group = "verification"
    description = "Runs the plugin integration suite against a real Docker daemon."
    dependsOn(":plugin:integrationTest", ":spring-boot-plugin:integrationTest")
}

tasks.named<Wrapper>("wrapper") {
    gradleVersion = "8.14.3"
    distributionSha256Sum = "bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"
}
