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

package io.github.gregwhitaker.awsemulator.aws.sqs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class SqsTaskFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest
    @ValueSource(strings = {"groovy", "kotlin"})
    void typedTasksConfigureLazilyInBothDslsAndReuseTheConfigurationCache(String language) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'sqs-fixture'\n");
        boolean kotlin = language.equals("kotlin");
        Files.writeString(project.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), kotlin ? """
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("this-command-does-not-exist") }
            tasks.register<CreateSqsQueueTask>("createQueue") {
                queueName.set("fixture.fifo")
                attributes.put("FifoQueue", "true")
                mode.set(EmulatorMode.DEVELOPMENT)
            }
            """ : """
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('this-command-does-not-exist') }
            tasks.register('createQueue', CreateSqsQueueTask) {
                queueName.set('fixture.fifo')
                attributes.put('FifoQueue', 'true')
                mode.set(EmulatorMode.DEVELOPMENT)
            }
            """);
        String output = runner(project, "tasks", "--all").build().getOutput();
        assertTrue(output.contains("AWS Emulator - SQS tasks"), output);
        assertTrue(output.contains("createQueue - Creates or reuses an SQS queue in AWS Emulator."), output);
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void rejectsInvalidQueueInputsBeforeStartingDocker() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'sqs-fixture'\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.sqs.CreateSqsQueueTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('this-command-does-not-exist') }
            tasks.register('badFifo', CreateSqsQueueTask) { queueName.set('events.fifo') }
            tasks.register('badTimeout', CreateSqsQueueTask) {
                queueName.set('events')
                apiCallTimeoutSeconds.set(0)
            }
            """);
        assertTrue(runner(project, "badFifo").buildAndFail().getOutput().contains("FIFO queues require"));
        assertTrue(runner(project, "badTimeout").buildAndFail().getOutput().contains("apiCallTimeoutSeconds must be positive"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void moduleConfiguresAllTaskTypesAndRegistersDevelopmentCommandsLazily() throws Exception {
        fixture("""
            tasks.register<CreateSqsQueuesTask>("createQueues") { queueNames.add("events") }
            tasks.register<CreateSqsQueueWithDlqTask>("createDlq") { queueName.set("events.fifo"); queueAttributes.put("FifoQueue", "true") }
            tasks.register<ListSqsQueuesTask>("customListQueues")
            tasks.register<PurgeSqsQueuesTask>("customPurgeQueues")
            tasks.register<PublishSqsTask>("customPublish")
            check(tasks.named<CreateSqsQueueWithDlqTask>("createDlq").get().deadletterQueueName.get() == "events-dlq.fifo")
            listOf("listSqsQueues", "purgeSqsQueues", "publishSqs").forEach {
                check((tasks.named(it).get() as AbstractAwsResourceTask).mode.get() == EmulatorMode.DEVELOPMENT)
            }
            """);
        String output = runner(project, "tasks", "--all").build().getOutput();
        for (String task : new String[] {"createQueues", "createDlq", "customListQueues", "customPurgeQueues",
            "customPublish", "listSqsQueues", "purgeSqsQueues", "publishSqs"}) {
            assertTrue(output.contains(task + " - "), output);
        }
        assertTrue(output.contains("AWS Emulator - SQS tasks"), output);
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        String help = runner(project, "help", "--task", "publishSqs").build().getOutput();
        for (String option : new String[] {"--mode", "--message", "--messageGroupId", "--queueNames"}) assertTrue(help.contains(option), help);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void validatesBulkDlqAndMessageInputsBeforeStartingDocker() throws Exception {
        fixture("""
            tasks.register<CreateSqsQueuesTask>("badQueues") { queueNames.set(listOf("valid", "bad/name")) }
            tasks.register<CreateSqsQueueWithDlqTask>("badDlq") { queueName.set("events"); deadletterQueueName.set("events") }
            tasks.register<PublishSqsTask>("badFifo") { queueNames.add("events.fifo"); messages.from("messages.txt") }
            tasks.register<PublishSqsTask>("badMessages") { queueNames.add("events"); messages.from("corrupt.tgz") }
            """);
        Files.writeString(project.resolve("messages.txt"), "message");
        Files.writeString(project.resolve("corrupt.tgz"), "not an archive");
        String output = runner(project, "badQueues", "badDlq", "badFifo", "badMessages", "--continue").buildAndFail().getOutput();
        for (String message : new String[] {"queueName must", "cannot be its own deadletter", "messageGroupId is required", "Cannot read SQS messages"}) {
            assertTrue(output.contains(message), output);
        }
        String cli = runner(project, "purgeSqsQueues", "--queueNames=valid,bad/name").buildAndFail().getOutput();
        assertTrue(cli.contains("queueName must"), cli);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    private void fixture(String tasks) throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"sqs-fixture\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
            import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask
            import io.github.gregwhitaker.awsemulator.aws.sqs.*
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("this-command-does-not-exist") }
            """ + tasks);
    }
}
