package io.github.gregwhitaker.awsemulator.boot;

import io.github.gregwhitaker.awsemulator.boot.fixtures.InheritedQueueTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.io.File;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class SpringBootAutoSetupFunctionalTest {
    @TempDir Path project;
    private static final List<String> TYPES = List.of(
        "s3.CreateS3BucketTask", "s3.CreateS3BucketsTask",
        "sqs.CreateSqsQueueTask", "sqs.CreateSqsQueuesTask", "sqs.CreateSqsQueueWithDlqTask",
        "sns.CreateSnsTopicTask", "sns.CreateSnsTopicWithSqsEndpointTask", "dynamodb.CreateDynamoDbTableTask",
        "ssm.PutSsmParameterTask", "ssm.PutSsmParametersTask", "secretsmanager.PutSecretTask", "secretsmanager.PutSecretsTask",
        "eventbridge.CreateEventBusTask", "eventbridge.PutEventBridgeRuleTask", "eventbridge.PutEventBridgeTargetsTask",
        "eventbridge.PutEventBridgeSqsTargetTask", "kinesis.CreateKinesisStreamTask", "kms.CreateKmsKeyTask", "kms.CreateKmsAliasTask",
        "cloudwatchlogs.CreateLogGroupTask", "cloudwatchlogs.CreateLogStreamTask",
        "ses.CreateSesEmailIdentityTask", "ses.PutSesEmailTemplateTask", "ses.CreateSesConfigurationSetTask", "ses.PutSesSnsEventDestinationTask");

    @ParameterizedTest
    @CsvSource({"3.5.16, bootRunLocal, DEVELOPMENT", "4.1.2, bootRunLocalDebug, MANAGED"})
    void discoversCreationTasksIncludingLateRegistrationsAndInheritedMarkers(String version, String consumer, String mode) throws Exception {
        StringBuilder registrations = new StringBuilder();
        for (String type : TYPES) {
            String name = "setup" + type.substring(type.indexOf('.') + 1);
            registrations.append("tasks.register('").append(name)
                .append("', io.github.gregwhitaker.awsemulator.aws.").append(type).append(")\n");
        }
        fixture(version, """
            // Realizing the consumer must not freeze the set of resource tasks.
            tasks.named('%s').get()
            awsEmulator.springBoot.mode.set(EmulatorMode.%s)
            %s
            tasks.register('inheritedQueue', io.github.gregwhitaker.awsemulator.boot.fixtures.InheritedQueueTask)
            tasks.register('customResource', io.github.gregwhitaker.awsemulator.boot.fixtures.CustomResourceTask)
            tasks.register('testOnlyQueue', CreateSqsQueueTask) { mode.set(EmulatorMode.MANAGED) }
            awsEmulator.springBoot.excludedSetupTasks.add(':testOnlyQueue')
            tasks.register('fixtureMessages', io.github.gregwhitaker.awsemulator.aws.sqs.PublishSqsTask)
            tasks.register('fixtureEmail', io.github.gregwhitaker.awsemulator.aws.ses.SendSesEmailTask)
            tasks.register('unrelatedTask') { throw new GradleException('Unrelated task was realized') }
            def consumer = tasks.named('%s').get()
            def dependencies = consumer.taskDependencies.getDependencies(consumer)
            assert dependencies.contains(tasks.named('inheritedQueue').get())
            assert dependencies.contains(tasks.named('customResource').get())
            assert tasks.named('inheritedQueue').get().mode.get() == EmulatorMode.%s
            assert tasks.named('setupCreateS3BucketTask').get().mode.get() == EmulatorMode.%s
            assert !dependencies.contains(tasks.named('testOnlyQueue').get())
            // Dependency resolution must also pick up tasks registered after an earlier query.
            tasks.register('lateQueue', CreateSqsQueueTask) { dependsOn('setupCreateS3BucketTask') }
            """.formatted(consumer, mode, registrations, consumer, mode, mode));
        for (int run = 0; run < 2; run++) {
            var build = runner(project, consumer, "--dry-run");
            var classpath = new ArrayList<File>(build.getPluginClasspath());
            classpath.add(new File(InheritedQueueTask.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
            String output = build.withPluginClasspath(classpath).build().getOutput();
            for (String type : TYPES) {
                String name = ":setup" + type.substring(type.indexOf('.') + 1) + " SKIPPED";
                assertTrue(output.contains(name), output);
                assertTrue(output.indexOf(name) < output.indexOf(":" + consumer + " SKIPPED"), output);
            }
            assertTrue(output.contains(":lateQueue SKIPPED"), output);
            assertTrue(output.contains(":inheritedQueue SKIPPED"), output);
            assertTrue(output.contains(":customResource SKIPPED"), output);
            assertTrue(output.indexOf(":setupCreateS3BucketTask SKIPPED") < output.indexOf(":lateQueue SKIPPED"), output);
            for (String excluded : List.of("testOnlyQueue", "fixtureMessages", "fixtureEmail", "sendSesEmail", "getSesMessageInsights", "publishSqs", "purgeSqsQueues", "deleteS3Buckets", "listS3Buckets", "bootRun")) {
                assertFalse(output.contains(":" + excluded + " SKIPPED"), output);
            }
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void doesNotChangeResourceDefaultsForOtherTasks() throws Exception {
        fixture("4.1.2", """
            def queue = tasks.register('createQueue', CreateSqsQueueTask)
            assert queue.get().mode.get() == EmulatorMode.MANAGED
            assert !tasks.named('bootRun').get().taskDependencies.getDependencies(tasks.named('bootRun').get()).contains(queue.get())
            """);
        String output = runner(project, "bootRun", "--dry-run").build().getOutput();
        assertFalse(output.contains(":createQueue SKIPPED"), output);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void autoCreationCanBeDisabledWhileExplicitSetupRemainsAvailable() throws Exception {
        fixture("4.1.2", """
            tasks.register('createQueue', CreateSqsQueueTask)
            tasks.register('explicitSetup')
            awsEmulator {
                springBoot {
                    autoCreateResources.set(false)
                    setupTasks.add('explicitSetup')
                }
            }
            """);
        String output = runner(project, "bootRunLocal", "--dry-run").build().getOutput();
        assertTrue(output.contains(":explicitSetup SKIPPED"), output);
        assertFalse(output.contains(":createQueue SKIPPED"), output);
        assertTrue(runner(project, "bootRunLocal", "--dry-run").build().getOutput().contains("Reusing configuration cache"));
    }

    @Test
    void rejectsAnExplicitlyConflictingModeBeforeStartingDocker() throws Exception {
        fixture("4.1.2", """
            tasks.register('testQueue', CreateSqsQueueTask) { mode.set(EmulatorMode.MANAGED) }
            """);
        String output = runner(project, "bootRunLocal", "--dry-run").buildAndFail().getOutput();
        assertTrue(output.contains("setup task :testQueue uses MANAGED but bootRunLocal uses DEVELOPMENT"), output);
        assertTrue(output.contains("excludedSetupTasks"), output);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void alignsResourcePrerequisitesWithoutDiscoveringUnrelatedFixtureTasks() throws Exception {
        fixture("4.1.2", """
            def prerequisite = tasks.register('prerequisite', io.github.gregwhitaker.awsemulator.aws.sqs.PublishSqsTask)
            tasks.register('createQueue', CreateSqsQueueTask) { dependsOn(prerequisite) }
            tasks.register('unrelatedFixture', io.github.gregwhitaker.awsemulator.aws.sqs.PublishSqsTask)
            def consumer = tasks.named('bootRunLocal').get()
            consumer.taskDependencies.getDependencies(consumer)
            assert prerequisite.get().mode.get() == EmulatorMode.DEVELOPMENT
            """);
        String output = runner(project, "bootRunLocal", "--dry-run").build().getOutput();
        assertTrue(output.indexOf(":prerequisite SKIPPED") < output.indexOf(":createQueue SKIPPED"), output);
        assertFalse(output.contains(":unrelatedFixture SKIPPED"), output);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void resourceSetsKeepManagedTestsSeparateFromBootAndScenarioSetup(boolean automatic) throws Exception {
        fixture("3.5.16", """
            def shared = awsEmulator.resourceSets.register('application') {
                provision('queue', CreateSqsQueueTask) { queueName.set('shared-events') }
                seed('messages', io.github.gregwhitaker.awsemulator.aws.sqs.PublishSqsTask) {
                    queueNames.add('shared-events')
                    messages.from('messages.txt')
                }
            }
            awsEmulator.resourceSets.register('unused') { throw new GradleException('Unused resource set realized') }
            awsEmulator {
                useWith(tasks.named('test'), EmulatorMode.MANAGED, shared)
                springBoot {
                    autoCreateResources.set(%s)
                    resourceSets.add(shared)
                }
                scenarios { register('default') { resources(shared) } }
            }
            """.formatted(automatic));
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "test", "bootRunLocal", "bootRunLocalDebug", "seedAwsEmulator", "--dry-run")
                .build().getOutput();
            for (String mode : List.of("MANAGED", "DEVELOPMENT")) {
                String queue = ":awsEmulatorResources_application_" + mode + "_queue SKIPPED";
                String messages = ":awsEmulatorResources_application_" + mode + "_messages SKIPPED";
                assertTrue(output.contains(queue), output);
                assertTrue(output.indexOf(queue) < output.indexOf(messages), output);
                assertEquals(1, output.split(java.util.regex.Pattern.quote(queue), -1).length - 1, output);
            }
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void sharedRuntimeAutomaticallyAddsOwnerResourcesToBootAndTestConsumers() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "include 'api', 'worker', 'localAws'\n");
        Files.writeString(project.resolve("build.gradle"), """
            plugins {
                id 'org.springframework.boot' version '4.1.2' apply false
                id 'io.github.gregwhitaker.aws-emulator.spring-boot' apply false
            }
            """);
        Files.createDirectories(project.resolve("localAws"));
        Files.writeString(project.resolve("localAws/build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator {
                dockerExecutable.set('no-docker-during-configuration')
                resourceSets.register('application') {
                    provision('queue', CreateSqsQueueTask) { queueName.set('shared-orders') }
                }
            }
            """);
        for (String child : List.of("api", "worker")) {
            Files.createDirectories(project.resolve(child));
            Files.writeString(project.resolve(child + "/build.gradle"), """
                plugins {
                    id 'java'
                    id 'org.springframework.boot'
                    id 'io.github.gregwhitaker.aws-emulator.spring-boot'
                }
                tasks.named('bootRun') { mainClass.set('example.Application') }
                tasks.named('bootRunLocal').get()
                awsEmulator.useWith(tasks.named('test'))
                tasks.named('test').get()
                awsEmulator.useSharedRuntime(':localAws', 'application')
                """);
        }
        for (int run = 0; run < 2; run++) {
            String output = runner(project, ":api:bootRunLocal", ":worker:bootRunLocalDebug", ":api:test", ":worker:test",
                "--parallel", "--dry-run").build().getOutput();
            for (String mode : List.of("MANAGED", "DEVELOPMENT")) {
                String setup = ":localAws:awsEmulatorResources_application_" + mode + "_queue SKIPPED";
                assertEquals(1, output.split(setup, -1).length - 1, output);
                for (String consumer : mode.equals("MANAGED") ? List.of(":api:test", ":worker:test")
                        : List.of(":api:bootRunLocal", ":worker:bootRunLocalDebug")) {
                    assertTrue(output.indexOf(setup) < output.indexOf(consumer + " SKIPPED"), output);
                }
            }
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        assertFalse(Files.exists(project.resolve("localAws/.aws-emulator")));
    }

    private void fixture(String version, String body) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'boot-setup-fixture'\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins {
                id 'java'
                id 'org.springframework.boot' version '%s'
                id 'io.github.gregwhitaker.aws-emulator.spring-boot'
            }
            awsEmulator { dockerExecutable.set('no-docker-during-configuration') }
            tasks.named('bootRun') { mainClass.set('example.Application') }
            %s
            """.formatted(version, body));
    }
}
