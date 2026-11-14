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

import io.github.gregwhitaker.awsemulator.aws.s3.CreateS3BucketsTask
import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueuesTask
import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueWithDlqTask
import io.github.gregwhitaker.awsemulator.aws.sqs.PublishSqsTask

plugins {
    java
    id("io.github.gregwhitaker.aws-emulator")
}

repositories { mavenCentral() }

val integrationSourceSet = sourceSets.create("integration")

dependencies {
    "integrationImplementation"(platform("software.amazon.awssdk:bom:2.39.6"))
    "integrationImplementation"("software.amazon.awssdk:sts")
    "integrationImplementation"("software.amazon.awssdk:s3")
    "integrationImplementation"("software.amazon.awssdk:sqs")
    "integrationImplementation"("software.amazon.awssdk:url-connection-client")
    "integrationImplementation"(platform("org.junit:junit-bom:5.11.4"))
    "integrationImplementation"("org.junit.jupiter:junit-jupiter")
    "integrationRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val createBuckets = tasks.register<CreateS3BucketsTask>("createExampleBuckets") {
    buckets.set(listOf("example-test-bucket", "example-uploads"))
}
val createQueue = tasks.register<CreateSqsQueueTask>("createExampleQueue") {
    queueName.set("example-test-queue")
    attributes.put("VisibilityTimeout", "45")
}
val createFifoQueue = tasks.register<CreateSqsQueueWithDlqTask>("createExampleFifoQueue") {
    queueName.set("example-events.fifo")
    queueAttributes.put("FifoQueue", "true")
    queueAttributes.put("ContentBasedDeduplication", "true")
    maxReceiveCount.set(3)
}
val createFixtureQueues = tasks.register<CreateSqsQueuesTask>("createFixtureQueues") {
    queueNames.set(listOf("example-seeded-queue", "example-secondary-queue"))
}
val publishFixtures = tasks.register<PublishSqsTask>("publishFixtures") {
    dependsOn(createFixtureQueues)
    queueNames.add("example-seeded-queue")
    messages.from(layout.projectDirectory.dir("src/integration/messages"))
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Verifies provisioned S3 and SQS resources using signed AWS SDK requests."
    dependsOn(createBuckets, createQueue, createFifoQueue, publishFixtures)
    group = "verification"
    testClassesDirs = integrationSourceSet.output.classesDirs
    classpath = integrationSourceSet.runtimeClasspath
    useJUnitPlatform()
    // Deliberately poisoned settings prove that useWith supplies the emulator connection.
    environment("AWS_ACCESS_KEY_ID", "not-a-real-access-key")
    environment("AWS_SECRET_ACCESS_KEY", "not-a-real-secret")
    environment("AWS_PROFILE", "not-a-real-profile")
    systemProperty("aws.accessKeyId", "not-a-real-access-key")
    systemProperty("aws.secretAccessKey", "not-a-real-secret")
    systemProperty("example.fail", providers.gradleProperty("failExample").getOrElse("false"))
    // Optional custom HTTPS trust is merged with this task's JDK roots and the emulator certificate.
    providers.gradleProperty("exampleTrustStore").orNull?.let {
        systemProperty("javax.net.ssl.trustStore", file(it).absolutePath)
    }
    providers.gradleProperty("exampleTrustStoreType").orNull?.let { systemProperty("javax.net.ssl.trustStoreType", it) }
    providers.environmentVariable("EXAMPLE_TRUST_STORE_PASSWORD").orNull?.let {
        systemProperty("javax.net.ssl.trustStorePassword", it)
    }
}

awsEmulator {
    useWith(integrationTest)
}
