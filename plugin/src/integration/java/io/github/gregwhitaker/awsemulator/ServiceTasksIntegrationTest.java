package io.github.gregwhitaker.awsemulator;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sqs.SqsClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class ServiceTasksIntegrationTest {
    @TempDir Path project;

    @Test
    void developmentCommandsListPublishPurgeAndDeleteTheirSelectedResources() throws Exception {
        fixture();
        try {
            runner(project, "provision").build();
            var clients = new AwsClientFactory(developmentConnection(), 30);
            try (var s3 = clients.s3(); var sqs = clients.sqs()) {
                s3.putObject(r -> r.bucket("fixture-bucket").key("data"), RequestBody.fromString("keep until purge"));
                versionedObjects(s3, "versioned-bucket", true);
                versionedObjects(s3, "delete-bucket", false);
                String listed = runner(project, "listS3Buckets", "listSqsQueues", "--prefix=events-").build().getOutput();
                assertTrue(listed.contains("fixture-bucket"), listed);
                assertTrue(listed.contains("versioned-bucket"), listed);
                assertTrue(listed.contains("events-one\t"), listed);
                assertFalse(listed.contains("ordered.fifo\t"), listed);

                String published = runner(project, "publishSqs", "--queueNames=events-one,events-two", "--message=messages").build().getOutput();
                assertTrue(published.contains("Published 6 SQS messages to 2 queues"), published);
                runner(project, "publishSqs", "--queueNames=ordered.fifo", "--message=fifo.txt", "--messageGroupId=fixture").build();
                for (String name : List.of("events-one", "events-two")) {
                    String url = queueUrl(sqs, name);
                    assertEquals(List.of("archived-one", "archived-two", "plain"), sqs.receiveMessage(r -> r.queueUrl(url)
                        .maxNumberOfMessages(10).waitTimeSeconds(5)).messages().stream().map(message -> message.body()).sorted().toList());
                }
                String fifoUrl = queueUrl(sqs, "ordered.fifo");
                assertEquals(2, sqs.receiveMessage(r -> r.queueUrl(fifoUrl).maxNumberOfMessages(10).waitTimeSeconds(5)).messages().size());
                String sourceUrl = queueUrl(sqs, "events-one");
                sqs.sendMessage(r -> r.queueUrl(sourceUrl).messageBody("survives DLQ update"));
                runner(project, "provision", "-PmaxReceiveCount=4").build();
                assertTrue(redrive(sqs, "events-one").contains("4"));
                assertTrue(redrive(sqs, "ordered.fifo").contains("ordered-dlq.fifo"));
                assertEquals("survives DLQ update", sqs.receiveMessage(r -> r.queueUrl(sourceUrl).waitTimeSeconds(5)).messages().get(0).body());

                String refused = runner(project, "deleteS3Buckets", "--buckets=delete-bucket").buildAndFail().getOutput();
                assertTrue(refused.contains("not empty"), refused);
                assertFalse(s3.listObjectVersions(r -> r.bucket("delete-bucket")).versions().isEmpty());
                runner(project, "purgeS3Buckets", "--buckets=fixture-bucket,versioned-bucket",
                    "deleteS3Buckets", "--buckets=delete-bucket", "--force").build();
                assertTrue(s3.listObjectsV2(r -> r.bucket("fixture-bucket")).contents().isEmpty());
                var versions = s3.listObjectVersions(r -> r.bucket("versioned-bucket"));
                assertTrue(versions.versions().isEmpty());
                assertTrue(versions.deleteMarkers().isEmpty());
                assertEquals(404, assertThrows(S3Exception.class, () -> s3.headBucket(r -> r.bucket("delete-bucket"))).statusCode());
                runner(project, "deleteS3Buckets", "--buckets=delete-bucket").build();

                runner(project, "purgeSqsQueues", "--queueNames=events-one,events-two,ordered.fifo").build();
                for (String name : List.of("events-one", "events-two", "ordered.fifo")) assertQueueEmpty(sqs, name);
            }
            assertFalse(containers().isBlank());
        } finally {
            runner(project, "stopAwsEmulator").build();
        }
        assertEquals("", containers());
    }

    @Test
    void bulkAndDlqSetupAndPublishingShareTheManagedTestRuntimeOnCacheReuse() throws Exception {
        fixture();
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "test", "listS3Buckets", "--mode=MANAGED", "listSqsQueues", "--mode=MANAGED", "-Pmanaged=true").build().getOutput();
            assertTrue(output.contains("Published 3 SQS messages to 1 queue"), output);
            assertTrue(output.contains("QueueName\tQueueUrl"), output);
            if (run == 1) assertTrue(output.contains("Reusing configuration cache"), output);
            assertEquals("", containers());
            try (var files = Files.list(project.resolve(".aws-emulator"))) {
                assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith("managed-")));
            }
        }
    }

    private void versionedObjects(S3Client s3, String bucket, boolean suspended) {
        s3.putBucketVersioning(r -> r.bucket(bucket).versioningConfiguration(c -> c.status(BucketVersioningStatus.ENABLED)));
        s3.putObject(r -> r.bucket(bucket).key("data"), RequestBody.fromString("old"));
        s3.putObject(r -> r.bucket(bucket).key("data"), RequestBody.fromString("new"));
        s3.deleteObject(r -> r.bucket(bucket).key("data"));
        if (suspended) {
            s3.putBucketVersioning(r -> r.bucket(bucket).versioningConfiguration(c -> c.status(BucketVersioningStatus.SUSPENDED)));
            s3.putObject(r -> r.bucket(bucket).key("null-version"), RequestBody.fromString("null version"));
        }
    }

    private String queueUrl(SqsClient sqs, String name) {
        return sqs.getQueueUrl(r -> r.queueName(name)).queueUrl();
    }

    private String redrive(SqsClient sqs, String name) {
        return sqs.getQueueAttributes(r -> r.queueUrl(queueUrl(sqs, name)).attributeNamesWithStrings("RedrivePolicy"))
            .attributesAsStrings().get("RedrivePolicy");
    }

    private void assertQueueEmpty(SqsClient sqs, String name) throws Exception {
        String url = queueUrl(sqs, name);
        long deadline = System.nanoTime() + Duration.ofSeconds(65).toNanos();
        Map<String, String> counts;
        do {
            counts = sqs.getQueueAttributes(r -> r.queueUrl(url).attributeNamesWithStrings(
                "ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible", "ApproximateNumberOfMessagesDelayed")).attributesAsStrings();
            if (counts.values().stream().allMatch("0"::equals)) return;
            Thread.sleep(1000);
        } while (System.nanoTime() < deadline);
        fail("Queue was not empty after purge: " + name + " " + counts);
    }

    private EmulatorConnection developmentConnection() throws Exception {
        try (var directories = Files.list(project.resolve(".aws-emulator/development/.aws/emulator"))) {
            Path directory = directories.filter(Files::isDirectory).findFirst().orElseThrow();
            return new EmulatorConnection(Integer.parseInt(directory.getFileName().toString()), "us-east-1", directory);
        }
    }

    private String containers() throws Exception {
        return new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking service task cleanup");
    }

    private void fixture() throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"service-task-fixture\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
            import io.github.gregwhitaker.awsemulator.aws.s3.*
            import io.github.gregwhitaker.awsemulator.aws.sqs.*
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { java; id("io.github.gregwhitaker.aws-emulator") }
            repositories { mavenCentral() }
            dependencies {
                testImplementation(platform("software.amazon.awssdk:bom:2.39.6"))
                testImplementation("software.amazon.awssdk:s3")
                testImplementation("software.amazon.awssdk:sqs")
                testImplementation("software.amazon.awssdk:url-connection-client")
                testImplementation(platform("org.junit:junit-bom:5.11.4"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            val runtimeMode = if (providers.gradleProperty("managed").getOrElse("false").toBoolean())
                EmulatorMode.MANAGED else EmulatorMode.DEVELOPMENT
            val buckets = tasks.register<CreateS3BucketsTask>("createBuckets") {
                mode.set(runtimeMode)
                buckets.set(listOf("fixture-bucket", "versioned-bucket", "delete-bucket"))
            }
            val queues = tasks.register<CreateSqsQueuesTask>("createQueues") {
                mode.set(runtimeMode)
                queueNames.set(listOf("events-one", "events-two"))
                queueAttributes.put("VisibilityTimeout", "45")
            }
            val dlq = tasks.register<CreateSqsQueueWithDlqTask>("createDlq") {
                dependsOn(queues)
                mode.set(runtimeMode)
                queueName.set("events-one")
                queueAttributes.put("VisibilityTimeout", "45")
                maxReceiveCount.set(providers.gradleProperty("maxReceiveCount").map(String::toInt).orElse(2))
            }
            val fifo = tasks.register<CreateSqsQueueWithDlqTask>("createFifo") {
                mode.set(runtimeMode)
                queueName.set("ordered.fifo")
                queueAttributes.put("FifoQueue", "true")
            }
            val provision = tasks.register("provision") { dependsOn(buckets, dlq, fifo) }
            val publish = tasks.register<PublishSqsTask>("publishManaged") {
                dependsOn(provision)
                queueNames.add("events-one")
                messages.from("messages")
            }
            val tests = tasks.named<Test>("test") { dependsOn(publish); useJUnitPlatform() }
            awsEmulator { port.set(0); useWith(tests) }
            """);
        Path messages = Files.createDirectory(project.resolve("messages"));
        Files.writeString(messages.resolve("plain.txt"), "plain\n");
        Files.writeString(messages.resolve(".ignored"), "ignored\n");
        Files.writeString(project.resolve("fifo.txt"), "identical\nidentical\n");
        try (var archive = new TarArchiveOutputStream(new GzipCompressorOutputStream(Files.newOutputStream(messages.resolve("archive.tgz"))))) {
            for (var file : Map.of("nested/events.txt", "archived-one\narchived-two\n", ".hidden", "ignored\n").entrySet()) {
                byte[] bytes = file.getValue().getBytes(StandardCharsets.UTF_8);
                var entry = new TarArchiveEntry(file.getKey());
                entry.setSize(bytes.length);
                archive.putArchiveEntry(entry);
                archive.write(bytes);
                archive.closeArchiveEntry();
            }
        }
        Path test = project.resolve("src/test/java/ResourcesTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, """
            import org.junit.jupiter.api.Test;
            import software.amazon.awssdk.services.s3.S3Client;
            import software.amazon.awssdk.services.sqs.SqsClient;
            import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
            import static org.junit.jupiter.api.Assertions.*;
            class ResourcesTest {
                @Test void consumesResourcesAndMessagesPreparedByTasks() {
                    try (var s3 = S3Client.builder().forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
                         var sqs = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
                        assertEquals(3, s3.listBuckets().buckets().size());
                        assertEquals(5, sqs.listQueues().queueUrls().size());
                        String url = sqs.getQueueUrl(r -> r.queueName("events-one")).queueUrl();
                        assertEquals(3, sqs.receiveMessage(r -> r.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(5)).messages().size());
                        String policy = sqs.getQueueAttributes(r -> r.queueUrl(url).attributeNamesWithStrings("RedrivePolicy")).attributesAsStrings().get("RedrivePolicy");
                        assertTrue(policy.contains("events-one-dlq"));
                    }
                }
            }
            """);
    }
}
