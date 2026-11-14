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

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask
import io.github.gregwhitaker.awsemulator.aws.cloudformation.DeployCloudFormationStackTask
import io.github.gregwhitaker.awsemulator.aws.cloudformation.ValidateCloudFormationTemplateTask
import io.github.gregwhitaker.awsemulator.model.EmulatorMode

plugins { java; id("io.github.gregwhitaker.aws-emulator") }
repositories { mavenCentral() }

val integration = sourceSets.create("integration")
dependencies {
    "integrationImplementation"(platform("software.amazon.awssdk:bom:2.39.6"))
    for (service in listOf("s3", "sqs", "dynamodb", "url-connection-client")) {
        "integrationImplementation"("software.amazon.awssdk:$service")
    }
    "integrationImplementation"("com.fasterxml.jackson.core:jackson-databind:2.18.6")
    "integrationImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "integrationImplementation"("org.junit.jupiter:junit-jupiter")
    "integrationRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
val runtimeMode = if (providers.gradleProperty("development").map(String::toBoolean).getOrElse(false))
    EmulatorMode.DEVELOPMENT else EmulatorMode.MANAGED
tasks.withType<AbstractAwsResourceTask>().configureEach {
    if (runtimeMode == EmulatorMode.DEVELOPMENT) mode.set(runtimeMode)
}
val template = layout.projectDirectory.file("fixtures/stack.yaml")
tasks.named<ValidateCloudFormationTemplateTask>("validateCloudFormationTemplate") { templateFile.set(template) }
val stack = tasks.register<DeployCloudFormationStackTask>("deployExampleStack") {
    stackName.set("cloudformation-example")
    templateFile.set(template)
    parameters.put("QueueName", providers.gradleProperty("queueName").orElse("cloudformation-example-events"))
    tags.put("example", "cloudformation")
}
val integrationTest = tasks.register<Test>("integrationTest") {
    dependsOn(stack)
    testClassesDirs = integration.output.classesDirs
    classpath = integration.runtimeClasspath
    useJUnitPlatform()
    testLogging.showStandardStreams = true
    inputs.file(stack.flatMap { it.outputsFile })
    systemProperty("cloudformation.outputs", stack.flatMap { it.outputsFile }.get().asFile.absolutePath)
    systemProperty("example.fail", providers.gradleProperty("failExample").getOrElse("false"))
}
awsEmulator { region.set("us-east-1"); useWith(integrationTest, runtimeMode) }
