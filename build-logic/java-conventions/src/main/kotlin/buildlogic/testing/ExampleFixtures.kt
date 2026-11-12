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

package buildlogic.testing

import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

/**
 * Exposes an example through `<camelCaseName>.example` and tracks its fixture files.
 * Set [multiProject] to include nested build scripts and source directories.
 */
fun Test.useExample(name: String, multiProject: Boolean = false) {
    val parts = name.split('-')
    val propertyPrefix = parts.first() + parts.drop(1).joinToString("") {
        it.replaceFirstChar { character -> character.uppercaseChar() }
    }
    useFixture(
        systemPropertyName = "$propertyPrefix.example",
        inputName = "${propertyPrefix}Example",
        directory = "examples/$name",
        includes = if (multiProject) {
            listOf("**/build.gradle.kts", "settings.gradle.kts", "**/src/**")
        } else {
            listOf("build.gradle.kts", "src/**", "fixtures/**")
        },
        excludes = if (multiProject) listOf("**/build/**", "**/.gradle/**") else emptyList()
    )
}

/** Exposes the examples root as `framework.examples` and tracks the selected frameworks. */
fun Test.useFrameworkExamples(vararg frameworks: String) {
    useFixture(
        systemPropertyName = "framework.examples",
        inputName = "frameworkExamples",
        directory = "examples",
        includes = frameworks.flatMap { framework ->
            listOf("$framework/build.gradle.kts", "$framework/settings.gradle.kts", "$framework/src/**")
        }
    )
}

private fun Test.useFixture(
    systemPropertyName: String,
    inputName: String,
    directory: String,
    includes: List<String>,
    excludes: List<String> = emptyList()
) {
    val fixtureDirectory = project.rootProject.layout.projectDirectory.dir(directory).asFile
    val fixtureFiles = project.fileTree(fixtureDirectory).apply {
        include(includes)
        exclude(excludes)
    }
    systemProperty(systemPropertyName, fixtureDirectory.absolutePath)
    inputs.files(fixtureFiles).withPropertyName(inputName).withPathSensitivity(PathSensitivity.RELATIVE)
}
