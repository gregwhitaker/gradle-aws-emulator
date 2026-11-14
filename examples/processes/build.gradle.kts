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

import io.github.gregwhitaker.awsemulator.model.EmulatorMode

plugins {
    id("io.github.gregwhitaker.aws-emulator")
}

val selectedMode = providers.gradleProperty("development").map {
    if (it.toBoolean()) EmulatorMode.DEVELOPMENT else EmulatorMode.MANAGED
}.orElse(EmulatorMode.MANAGED)
val failExample = providers.gradleProperty("failExample").orElse("false")

val pythonProbe = tasks.register<Exec>("pythonProbe") {
    description = "Checks emulator HTTPS using Python's standard library."
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"), "probe.py", failExample.get())
}
val nodeProbe = tasks.register<Exec>("nodeProbe") {
    description = "Checks emulator HTTPS using Node's standard library."
    commandLine(providers.gradleProperty("nodeExecutable").getOrElse("node"), "probe.mjs", failExample.get())
}
val awsIdentity = tasks.register<Exec>("awsIdentity") {
    description = "Makes a signed STS call using an installed AWS CLI v2."
    commandLine(providers.gradleProperty("awsExecutable").getOrElse("aws"), "sts", "get-caller-identity", "--no-cli-pager")
    // useWith prepends connection setup; resolve the endpoint after that setup, at execution time.
    doFirst {
        args("--endpoint-url", environment.getValue("AWS_ENDPOINT_URL"))
    }
}
awsEmulator {
    useWith(pythonProbe, selectedMode)
    useWith(nodeProbe, selectedMode)
    useWith(awsIdentity, selectedMode)
}
