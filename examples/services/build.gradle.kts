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

import io.github.gregwhitaker.awsemulator.aws.ssm.*
import io.github.gregwhitaker.awsemulator.aws.secretsmanager.*
import io.github.gregwhitaker.awsemulator.aws.eventbridge.*
import io.github.gregwhitaker.awsemulator.aws.kinesis.*
import io.github.gregwhitaker.awsemulator.aws.kms.*
import io.github.gregwhitaker.awsemulator.aws.cloudwatchlogs.*
import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask
import io.github.gregwhitaker.awsemulator.model.EmulatorMode

plugins { java; id("io.github.gregwhitaker.aws-emulator") }
repositories { mavenCentral() }
val integration = sourceSets.create("integration")
dependencies {
    "integrationImplementation"(platform("software.amazon.awssdk:bom:2.39.6"))
    for (service in listOf("ssm", "secretsmanager", "eventbridge", "kinesis", "kms", "cloudwatchlogs", "sqs", "url-connection-client")) {
        "integrationImplementation"("software.amazon.awssdk:$service")
    }
    "integrationImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "integrationImplementation"("org.junit.jupiter:junit-jupiter")
    "integrationRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
val development = providers.gradleProperty("development").map(String::toBoolean).getOrElse(false)
val replaceValues = providers.gradleProperty("overwrite").map(String::toBoolean).orElse(false)
if (development) tasks.withType<AbstractAwsResourceTask>().configureEach { mode.set(EmulatorMode.DEVELOPMENT) }
val key = tasks.register<CreateKmsKeyTask>("createFixtureKey") { aliasName.set("alias/example-services") }
val parameters = tasks.register<PutSsmParametersTask>("seedParameters") {
    dependsOn(key)
    fixtures.set(layout.projectDirectory.file("fixtures/parameters.json"))
    overwrite.set(replaceValues)
}
val secrets = tasks.register<PutSecretsTask>("seedSecrets") {
    dependsOn(key)
    fixtures.set(layout.projectDirectory.file("fixtures/secrets.json"))
    overwrite.set(replaceValues)
}
val queue = tasks.register<CreateSqsQueueTask>("createEventQueue") { queueName.set("example-service-events") }
val bus = tasks.register<CreateEventBusTask>("createFixtureBus") { eventBusName.set("example-services") }
val rule = tasks.register<PutEventBridgeRuleTask>("createFixtureRule") {
    dependsOn(bus)
    eventBusName.set("example-services"); ruleName.set("orders")
    eventPattern.set("""{"source":["example.orders"]}""")
}
val target = tasks.register<PutEventBridgeSqsTargetTask>("connectEventQueue") {
    dependsOn(rule, queue)
    eventBusName.set("example-services"); ruleName.set("orders")
    queueName.set("example-service-events"); targetId.set("orders-queue")
}
val stream = tasks.register<CreateKinesisStreamTask>("createFixtureStream") { streamName.set("example-service-records") }
val logGroupTask = tasks.register<CreateLogGroupTask>("createFixtureLogGroup") { logGroupName.set("/example/services") }
val logs = tasks.register<CreateLogStreamTask>("createFixtureLogStream") {
    dependsOn(logGroupTask); logGroupName.set("/example/services"); logStreamName.set("application")
}
val provision = tasks.register("provisionServices") { dependsOn(parameters, secrets, target, stream, logs) }
val events = tasks.register<PublishEventBridgeEventsTask>("publishFixtureEvents") {
    dependsOn(target); eventBusName.set("example-services"); fixtures.set(layout.projectDirectory.file("fixtures/events.json"))
}
val records = tasks.register<PublishKinesisRecordsTask>("publishFixtureRecords") {
    dependsOn(stream); streamName.set("example-service-records"); fixtures.set(layout.projectDirectory.file("fixtures/records.json"))
}
val encrypt = tasks.register<EncryptKmsFileTask>("encryptFixture") {
    dependsOn(key); keyId.set("alias/example-services")
    inputFile.set(layout.projectDirectory.file("fixtures/plaintext.txt"))
    outputFile.set(layout.buildDirectory.file("fixtures/ciphertext.bin"))
    encryptionContext.put("application", "example")
}
val decrypt = tasks.register<DecryptKmsFileTask>("decryptFixture") {
    keyId.set("alias/example-services")
    inputFile.set(encrypt.flatMap { it.outputFile })
    outputFile.set(layout.buildDirectory.file("fixtures/decrypted.txt"))
    encryptionContext.put("application", "example")
}
val integrationTest = tasks.register<Test>("integrationTest") {
    dependsOn(provision, events, records, decrypt)
    testClassesDirs = integration.output.classesDirs
    classpath = integration.runtimeClasspath
    useJUnitPlatform()
    // useWith supplies JSON settings to the child JVM, even when inherited options enable CBOR.
    environment("CBOR_ENABLED", "true")
    systemProperty("aws.cborEnabled", "true")
    systemProperty("example.decrypted", layout.buildDirectory.file("fixtures/decrypted.txt").get().asFile.absolutePath)
    systemProperty("example.fail", providers.gradleProperty("failExample").getOrElse("false"))
}
val filtered = tasks.register<FilterLogEventsTask>("filterFixtureLogs") {
    dependsOn(integrationTest); logGroupName.set("/example/services"); filterPattern.set("ready")
    minimumEvents.set(1); waitTimeoutSeconds.set(10)
    outputFile.set(layout.buildDirectory.file("logs/filtered.json"))
}
val retrieved = tasks.register<GetLogEventsTask>("getFixtureLogs") {
    dependsOn(integrationTest); logGroupName.set("/example/services"); logStreamName.set("application")
    outputFile.set(layout.buildDirectory.file("logs/events.json"))
}
tasks.register("verifyServices") { dependsOn(filtered, retrieved); group = "verification" }
// Configuration-cache execution may run independent tasks concurrently, even in one project.
tasks.named("deleteEventBus") { mustRunAfter("deleteEventBridgeRule") }
tasks.named("deleteLogGroup") { mustRunAfter("deleteLogStream") }
awsEmulator { useWith(integrationTest) }
