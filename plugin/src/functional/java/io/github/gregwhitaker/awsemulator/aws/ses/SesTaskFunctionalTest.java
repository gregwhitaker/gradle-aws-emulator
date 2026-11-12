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

package io.github.gregwhitaker.awsemulator.aws.ses;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class SesTaskFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest @ValueSource(strings = {"groovy", "kotlin"})
    void configuresBothDslsLazilyWithCorrectLifetimes(String language) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'ses-functional'\n");
        boolean kotlin = language.equals("kotlin");
        Files.writeString(project.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), kotlin ? """
            import io.github.gregwhitaker.awsemulator.aws.ses.*
            import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("missing-docker-command") }
            val identity = tasks.register<CreateSesEmailIdentityTask>("sender") { emailIdentity.set("sender@example.test") }
            tasks.register<PutSesEmailTemplateTask>("template") { templateName.set("welcome"); subject.set("Hello"); textBody.set("Welcome") }
            tasks.register<CreateSesConfigurationSetTask>("configuration") { configurationSetName.set("mail") }
            tasks.register<PutSesSnsEventDestinationTask>("events") {
                configurationSetName.set("mail"); eventDestinationName.set("events"); topicArn.set("arn:aws:sns:us-east-1:000000000000:events")
            }
            val send = tasks.register<SendSesEmailTask>("send") { fromAddress.set("sender@example.test"); toAddresses.add("to@example.test"); subject.set("Hi"); htmlBody.set("<p>Hi</p>") }
            tasks.register<GetSesMessageInsightsTask>("insights") { messageId.set("example-id") }
            check(identity.get().mode.get() == EmulatorMode.MANAGED)
            check(send.get().messageIdFile.get().asFile.name == "message-id.txt")
            listOf("listSesEmailIdentities", "deleteSesEmailIdentity", "deleteSesEmailTemplate", "listSesConfigurationSets", "deleteSesConfigurationSet", "sendSesEmail", "getSesMessageInsights").forEach {
                check((tasks.named(it).get() as AbstractAwsResourceTask).mode.get() == EmulatorMode.DEVELOPMENT)
            }
            """ : """
            import io.github.gregwhitaker.awsemulator.aws.ses.*
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('missing-docker-command') }
            def identity = tasks.register('sender', CreateSesEmailIdentityTask) { emailIdentity.set('sender@example.test') }
            tasks.register('template', PutSesEmailTemplateTask) { templateName.set('welcome'); subject.set('Hello'); textBody.set('Welcome') }
            tasks.register('configuration', CreateSesConfigurationSetTask) { configurationSetName.set('mail') }
            tasks.register('events', PutSesSnsEventDestinationTask) {
                configurationSetName.set('mail'); eventDestinationName.set('events'); topicArn.set('arn:aws:sns:us-east-1:000000000000:events')
            }
            def send = tasks.register('send', SendSesEmailTask) { fromAddress.set('sender@example.test'); toAddresses.add('to@example.test'); subject.set('Hi'); htmlBody.set('<p>Hi</p>') }
            tasks.register('insights', GetSesMessageInsightsTask) { messageId.set('example-id') }
            assert identity.get().mode.get() == EmulatorMode.MANAGED
            assert send.get().messageIdFile.get().asFile.name == 'message-id.txt'
            ['listSesEmailIdentities', 'deleteSesEmailIdentity', 'deleteSesEmailTemplate', 'listSesConfigurationSets', 'deleteSesConfigurationSet', 'sendSesEmail', 'getSesMessageInsights'].each {
                assert tasks.named(it).get().mode.get() == EmulatorMode.DEVELOPMENT
            }
            """);
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("AWS Emulator - SES tasks"));
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        assertTrue(runner(project, "help", "--task", "deleteSesEmailIdentity").build().getOutput().contains("--emailIdentity"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test void rejectsInvalidInputsBeforeDockerWithoutPrintingEmailContent() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'ses-invalid'\n");
        Files.writeString(project.resolve("raw.eml"), "Subject: private-email-content\n\nBody\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.ses.*
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('missing-docker-command') }
            tasks.register('identity', CreateSesEmailIdentityTask) { emailIdentity.set('not a domain') }
            tasks.register('template', PutSesEmailTemplateTask) { templateName.set('welcome'); subject.set('private-email-content') }
            tasks.register('raw', SendSesEmailTask) {
                fromAddress.set('sender@example.test'); toAddresses.add('to@example.test'); subject.set('private-email-content'); rawMessage.set(file('raw.eml'))
            }
            tasks.register('empty', SendSesEmailTask) { fromAddress.set('sender@example.test'); subject.set('private-email-content'); textBody.set('private-email-content') }
            tasks.register('events', PutSesSnsEventDestinationTask) {
                configurationSetName.set('mail'); eventDestinationName.set('events'); topicArn.set('arn:aws:sns:us-east-1:000000000000:events'); matchingEventTypes.set(['INVALID'])
            }
            """);
        String output = runner(project, "identity", "template", "raw", "empty", "events", "--continue").buildAndFail().getOutput();
        for (String expected : new String[] {"emailIdentity must", "At least one of textBody", "rawMessage cannot", "Supply 1–50", "matchingEventTypes must"}) {
            assertTrue(output.contains(expected), output);
        }
        assertFalse(output.contains("private-email-content"), output);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }
}
