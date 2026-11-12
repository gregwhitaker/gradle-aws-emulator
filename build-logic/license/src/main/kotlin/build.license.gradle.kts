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
    id("net.minecraftforge.licenser")
}

val licenseHeader = resources.text.fromString(
    checkNotNull(javaClass.getResource("/build.license/license-header.txt")) {
        "The build.license plugin is missing its bundled license header."
    }.readText()
)

license {
    header.set(licenseHeader)
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
