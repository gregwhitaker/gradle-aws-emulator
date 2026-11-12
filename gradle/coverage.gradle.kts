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

import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

configure<JacocoPluginExtension> {
    toolVersion = providers.gradleProperty("jacocoVersion").get()
}

// Give each TestKit suite its own offline-agent settings. Gradle's test agent
// clears the same execution-data file before each run, preventing stale coverage.
for (suite in listOf("functionalTest", "integrationTest")) {
    val agentDirectory = layout.buildDirectory.dir("generated/jacoco/$suite")
    val executionFile = layout.buildDirectory.file("jacoco/$suite.exec")
    val agentProperties = tasks.register<WriteProperties>("${suite}JacocoProperties") {
        destinationFile.set(agentDirectory.map { it.file("jacoco-agent.properties") })
        property("destfile", executionFile.get().asFile.absolutePath)
        // The TestKit coverage plugin dumps after each build, including cache hits.
        property("dumponexit", "false")
    }
    tasks.named<Test>(suite) {
        inputs.files(agentProperties).withPropertyName("testKitJacocoProperties")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        systemProperty("test.jacoco.properties", agentDirectory.get().asFile.absolutePath)
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    description = "Generates coverage from unit and Docker-free functional tests."
    dependsOn(tasks.named("test"), tasks.named("functionalTest"))
    executionData(tasks.named<Test>("functionalTest").get())
}

val mainSources = the<SourceSetContainer>().named("main")
val integrationReport = tasks.register<JacocoReport>("jacocoIntegrationTestReport") {
    group = "verification"
    description = "Generates coverage from real-emulator integration tests (requires Docker)."
    dependsOn(tasks.named("integrationTest"))
    executionData(tasks.named<Test>("integrationTest").get())
    sourceSets(mainSources.get())
}
tasks.named("integrationTest") { finalizedBy(integrationReport) }

tasks.withType<JacocoReport>().configureEach {
    val suite = if (name == "jacocoTestReport") "test" else "integrationTest"
    val reportName = name
    reports {
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/$suite/html"))
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/$suite/$reportName.xml"))
        csv.required.set(false)
    }
}
tasks.named("check") { dependsOn(tasks.named("jacocoTestReport")) }
