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

import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask

plugins {
    java
    id("com.github.spotbugs")
}

spotbugs {
    toolVersion.set("4.10.5")
    effort.set(Effort.MAX)
    reportLevel.set(Confidence.MEDIUM)
    ignoreFailures.set(false)
    // TestKit fixtures deliberately exercise invalid code and failure paths.
    runOnCheck.set(false)

    val baseline = layout.projectDirectory.file("config/spotbugs/baseline.xml")
    if (baseline.asFile.isFile && providers.gradleProperty("spotbugsBaseline")
            .map { it.toBooleanStrict() }.getOrElse(true)) {
        baselineFile.set(baseline)
    }
}

tasks.withType<SpotBugsTask>().configureEach {
    reports.create("html") { required.set(true) }
    reports.create("xml") { required.set(true) }
}

tasks.named("check") { dependsOn(tasks.named("spotbugsMain")) }
