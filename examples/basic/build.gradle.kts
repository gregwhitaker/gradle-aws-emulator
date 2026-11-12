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
}

awsEmulator {
    // Zero selects a host port, then gives that same nonzero port to Docker and the emulator.
    port.set(providers.gradleProperty("awsEmulator.port").map(String::toInt).orElse(0))
    useWith(integrationTest)
}
