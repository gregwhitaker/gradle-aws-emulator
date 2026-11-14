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
    id("com.diffplug.spotless")
}

// Match the repository's existing layout without imposing a new Java/Kotlin style.
// File trees prune generated directories, including the independent consumer builds.
val generatedFiles = listOf(
    "**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/.aws-emulator/**", "**/.idea/**",
    "**/node_modules/**", "**/fixtures/**", "gradle/wrapper/**"
)

spotless {
    format("sources") {
        target(fileTree(rootDir) {
            include("**/*.java", "**/*.kt", "**/*.kts")
            exclude(generatedFiles)
        })
        leadingTabsToSpaces(4)
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("misc") {
        target(fileTree(rootDir) {
            include(
                "**/*.css", "**/*.js", "**/*.mjs", "**/*.py", "**/*.sh",
                "**/*.properties", "**/*.xml", "**/*.html", "**/*.svg", "**/*.yml", "**/*.yaml",
                ".gitignore", ".gitattributes"
            )
            exclude(generatedFiles)
        })
        trimTrailingWhitespace()
        endWithNewline()
    }
}
