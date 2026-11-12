package io.github.gregwhitaker.awsemulator;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;

import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class ProvisioningIntegrationTest {
    @TempDir Path project;

    @Test
    void managedResourcesSurviveRepeatedProvisioningAndAreFreshOnConfigurationCacheReuse() throws Exception {
        fixture();
        String first = isolated("test").build().getOutput();
        assertTrue(first.contains("S3 bucket ready: fixture-bucket"), first);
        assertTrue(first.contains("SQS queue ready: fixture-events.fifo"), first);
        assertManagedCleanup();
        String second = isolated("test").build().getOutput();
        assertTrue(second.contains("Reusing configuration cache"), second);
        assertTrue(second.contains("AWS Emulator ready at"), second);
        assertManagedCleanup();
    }

    @Test
    void failedSdkProvisioningCleansUpTheManagedRuntime() throws Exception {
        fixture();
        String output = isolated("badQueue").buildAndFail().getOutput();
        assertTrue(output.contains("S3 bucket ready: fixture-bucket"), output);
        assertTrue(output.contains("VisibilityTimeout"), output);
        assertManagedCleanup();
    }

    @Test
    void developmentResourcesPersistAndAttributeDriftFailsWithoutChangingData() throws Exception {
        fixture();
        try {
            isolated("developmentResources").build();
            EmulatorConnection connection;
            try (var directories = Files.list(project.resolve(".aws-emulator/development/.aws/emulator"))) {
                Path directory = directories.filter(Files::isDirectory).findFirst().orElseThrow();
                connection = new EmulatorConnection(Integer.parseInt(directory.getFileName().toString()), "eu-west-1", directory);
            }
            String original = containers();
            var clients = new AwsClientFactory(connection, 30);
            try (var s3 = clients.s3(); var sqs = clients.sqs()) {
                s3.putObject(request -> request.bucket("development-bucket").key("keep"), RequestBody.fromString("keep this data"));
                String url = sqs.getQueueUrl(request -> request.queueName("development-events")).queueUrl();
                sqs.sendMessage(request -> request.queueUrl(url).messageBody("keep this message"));
                String repeat = isolated("developmentResources").build().getOutput();
                assertTrue(repeat.contains("Reusing configuration cache"), repeat);
                assertEquals(original, containers());
                String drift = isolated("developmentResources", "-Pvisibility=46").buildAndFail().getOutput();
                assertTrue(drift.contains("different VisibilityTimeout"), drift);
                assertEquals(original, containers());
                assertEquals("keep this data", s3.getObjectAsBytes(request -> request.bucket("development-bucket").key("keep")).asUtf8String());
                assertEquals("45", sqs.getQueueAttributes(request -> request.queueUrl(url).attributeNamesWithStrings("VisibilityTimeout"))
                    .attributesAsStrings().get("VisibilityTimeout"));
                assertEquals("keep this message", sqs.receiveMessage(request -> request.queueUrl(url).waitTimeSeconds(5)).messages().get(0).body());
            }
        } finally {
            runner(project, "stopAwsEmulator").build();
        }
        assertEquals("", containers());
        assertFalse(Files.exists(project.resolve(".aws-emulator/development")));
    }

    private GradleRunner isolated(String... tasks) {
        var environment = new HashMap<>(System.getenv());
        environment.put("AWS_ACCESS_KEY_ID", "not-real");
        environment.put("AWS_SECRET_ACCESS_KEY", "not-real");
        environment.put("AWS_PROFILE", "nonexistent-profile");
        environment.put("AWS_SHARED_CREDENTIALS_FILE", project.resolve("nonexistent-credentials").toString());
        environment.put("AWS_CONFIG_FILE", project.resolve("nonexistent-config").toString());
        environment.put("AWS_ENDPOINT_URL", "https://127.0.0.1:1");
        environment.put("AWS_ENDPOINT_URL_S3", "https://127.0.0.1:1");
        environment.put("AWS_ENDPOINT_URL_SQS", "https://127.0.0.1:1");
        return runner(project, tasks).withEnvironment(environment);
    }

    private void assertManagedCleanup() throws Exception {
        assertEquals("", containers());
        if (Files.exists(project.resolve("connection.txt"))) {
            assertFalse(Files.exists(Path.of(Files.readString(project.resolve("connection.txt")))));
        }
        try (var files = Files.list(project.resolve(".aws-emulator"))) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith("managed-")));
        }
    }

    private String containers() throws Exception {
        return new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking provisioning cleanup");
    }

    private void fixture() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'provisioning-fixture'\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            import io.github.gregwhitaker.awsemulator.aws.s3.CreateS3BucketTask
            import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
            plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
            repositories { mavenCentral() }
            dependencies {
                implementation platform('software.amazon.awssdk:bom:2.39.6')
                implementation 'software.amazon.awssdk:s3'
                implementation 'software.amazon.awssdk:sqs'
                implementation 'software.amazon.awssdk:url-connection-client'
                testImplementation platform('org.junit:junit-bom:5.11.4')
                testImplementation 'org.junit.jupiter:junit-jupiter'
                testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
            }
            def bucket = tasks.register('createBucket', CreateS3BucketTask) { bucketName.set('fixture-bucket') }
            def queue = tasks.register('createQueue', CreateSqsQueueTask) {
                queueName.set('fixture-events')
                attributes.put('VisibilityTimeout', '45')
            }
            def fifo = tasks.register('createFifo', CreateSqsQueueTask) {
                queueName.set('fixture-events.fifo')
                attributes.put('FifoQueue', 'true')
                attributes.put('ContentBasedDeduplication', 'true')
            }
            def seed = tasks.register('seed', JavaExec) {
                dependsOn(bucket, queue, fifo)
                classpath = sourceSets.main.runtimeClasspath
                mainClass = 'Seed'
            }
            def repeatBucket = tasks.register('repeatBucket', CreateS3BucketTask) {
                dependsOn(seed)
                bucketName.set('fixture-bucket')
            }
            def repeatQueue = tasks.register('repeatQueue', CreateSqsQueueTask) {
                dependsOn(seed)
                queueName.set('fixture-events')
                attributes.put('VisibilityTimeout', '45')
            }
            def repeatFifo = tasks.register('repeatFifo', CreateSqsQueueTask) {
                dependsOn(seed)
                queueName.set('fixture-events.fifo')
                attributes.put('FifoQueue', 'true')
                attributes.put('ContentBasedDeduplication', 'true')
            }
            test {
                dependsOn(repeatBucket, repeatQueue, repeatFifo)
                useJUnitPlatform()
                systemProperty 'fixture.output', layout.projectDirectory.file('connection.txt').asFile.absolutePath
                testLogging { exceptionFormat = 'full' }
            }
            tasks.register('badQueue', CreateSqsQueueTask) {
                dependsOn(bucket)
                queueName.set('bad-events')
                attributes.put('VisibilityTimeout', '-1')
            }
            def developmentBucket = tasks.register('developmentBucket', CreateS3BucketTask) {
                mode.set(EmulatorMode.DEVELOPMENT)
                bucketName.set('development-bucket')
            }
            def developmentQueue = tasks.register('developmentQueue', CreateSqsQueueTask) {
                mode.set(EmulatorMode.DEVELOPMENT)
                queueName.set('development-events')
                attributes.put('VisibilityTimeout', providers.gradleProperty('visibility').getOrElse('45'))
            }
            tasks.register('developmentResources') { dependsOn(developmentBucket, developmentQueue) }
            awsEmulator {
                port.set(0)
                region.set('eu-west-1')
                useWith(seed)
                useWith(tasks.named('test'))
            }
            """);
        Path main = project.resolve("src/main/java/Seed.java");
        Files.createDirectories(main.getParent());
        Files.writeString(main, """
            import software.amazon.awssdk.core.sync.RequestBody;
            import software.amazon.awssdk.services.s3.S3Client;
            import software.amazon.awssdk.services.sqs.SqsClient;
            import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
            public class Seed {
                public static void main(String[] args) {
                    try (var s3 = S3Client.builder().forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
                         var sqs = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
                        if (!s3.listObjectsV2(r -> r.bucket("fixture-bucket")).contents().isEmpty()) {
                            throw new AssertionError("A managed build must start with fresh resources");
                        }
                        s3.putObject(r -> r.bucket("fixture-bucket").key("seed"), RequestBody.fromString("seeded object"));
                        for (String name : new String[] {"fixture-events", "fixture-events.fifo"}) {
                            String url = sqs.getQueueUrl(r -> r.queueName(name)).queueUrl();
                            sqs.sendMessage(r -> {
                                r.queueUrl(url).messageBody("seeded message");
                                if (name.endsWith(".fifo")) r.messageGroupId("fixture");
                            });
                        }
                    }
                }
            }
            """);
        Path test = project.resolve("src/test/java/ResourceTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, """
            import org.junit.jupiter.api.Test;
            import java.nio.file.*;
            import software.amazon.awssdk.services.s3.S3Client;
            import software.amazon.awssdk.services.sqs.SqsClient;
            import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
            import static org.junit.jupiter.api.Assertions.*;
            class ResourceTest {
                @Test void resourcesAreAvailableAndReprovisioningPreservesData() throws Exception {
                    Files.writeString(Path.of(System.getProperty("fixture.output")), System.getenv("AWS_SHARED_CREDENTIALS_FILE"));
                    try (var s3 = S3Client.builder().forcePathStyle(true).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
                         var sqs = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
                        assertEquals("seeded object", s3.getObjectAsBytes(r -> r.bucket("fixture-bucket").key("seed")).asUtf8String());
                        assertEquals("eu-west-1", s3.getBucketLocation(r -> r.bucket("fixture-bucket")).locationConstraintAsString());
                        for (String name : new String[] {"fixture-events", "fixture-events.fifo"}) {
                            String url = sqs.getQueueUrl(r -> r.queueName(name)).queueUrl();
                            assertEquals("seeded message", sqs.receiveMessage(r -> r.queueUrl(url).waitTimeSeconds(5)).messages().get(0).body());
                        }
                    }
                }
            }
            """);
    }
}
