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

package io.github.gregwhitaker.awsemulator.aws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class AwsModuleFunctionalTest {
    @TempDir Path project;
    private static final String IMPORTS = """
        import io.github.gregwhitaker.awsemulator.aws.ssm.*
        import io.github.gregwhitaker.awsemulator.aws.secretsmanager.*
        import io.github.gregwhitaker.awsemulator.aws.eventbridge.*
        import io.github.gregwhitaker.awsemulator.aws.kinesis.*
        import io.github.gregwhitaker.awsemulator.aws.kms.*
        import io.github.gregwhitaker.awsemulator.aws.cloudwatchlogs.*
        """;

    @Test void kotlinDslTasksAreLazyAndReuseConfigurationCache() throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"expanded-services\"");
        Files.writeString(project.resolve("build.gradle.kts"), IMPORTS + """
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("docker-must-not-run") }
            val parameter = tasks.register<PutSsmParameterTask>("parameter") { parameterName.set("/test/value"); value.set("local") }
            tasks.register<PutSecretsTask>("secrets") { fixtures.set(layout.projectDirectory.file("secrets.json")) }
            tasks.register<PutEventBridgeSqsTargetTask>("target") { eventBusName.set("bus"); ruleName.set("rule"); queueName.set("queue"); targetId.set("queue") }
            tasks.register<CreateKinesisStreamTask>("stream") { streamName.set("records") }
            tasks.register<CreateKmsKeyTask>("key") { aliasName.set("alias/test") }
            tasks.register<CreateLogGroupTask>("logs") { logGroupName.set("/test/logs") }
            check(parameter.get().mode.get() == EmulatorMode.MANAGED)
            check(tasks.named<ListSecretsTask>("listSecrets").get().mode.get() == EmulatorMode.DEVELOPMENT)
            """);
        String output = runner(project, "tasks", "--all").build().getOutput();
        for (String group : new String[] {"Parameter Store", "Secrets Manager", "EventBridge", "Kinesis", "KMS", "CloudWatch Logs"}) assertTrue(output.contains("AWS Emulator - " + group), output);
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test void groovyDslRegistersAllServicesWithoutStartingDocker() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'expanded-services'");
        Files.writeString(project.resolve("build.gradle"), IMPORTS + """
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('docker-must-not-run') }
            tasks.register('parameters', PutSsmParametersTask) { fixtures.set(layout.projectDirectory.file('parameters.json')) }
            tasks.register('secret', PutSecretTask) { secretName.set('test'); value.set('local') }
            tasks.register('bus', CreateEventBusTask) { eventBusName.set('events') }
            tasks.register('stream', CreateKinesisStreamTask) { streamName.set('records') }
            tasks.register('key', CreateKmsKeyTask) { aliasName.set('alias/test') }
            tasks.register('logs', CreateLogGroupTask) { logGroupName.set('/test/logs') }
            """);
        runner(project, "tasks", "--all").build();
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test void invalidFixturesAndOptionsFailBeforeDockerAndOmitValues() throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"invalid-services\"");
        Files.writeString(project.resolve("duplicate.json"), "[{\"name\":\"/test\",\"value\":\"hidden-fixture-value\"},{\"name\":\"/test\",\"value\":\"hidden-fixture-value\"}]");
        Files.writeString(project.resolve("secrets.json"), "[{\"name\":\"test\",\"value\":hidden-fixture-value}]");
        Files.writeString(project.resolve("events.json"), "[{\"source\":\"test\",\"detailType\":\"test\",\"detail\":\"hidden-fixture-value\"}]");
        Files.writeString(project.resolve("build.gradle.kts"), IMPORTS + """
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("docker-must-not-run") }
            tasks.register<PutSsmParametersTask>("badParameters") { fixtures.set(layout.projectDirectory.file("duplicate.json")) }
            tasks.register<PutSecretsTask>("badSecrets") { fixtures.set(layout.projectDirectory.file("secrets.json")) }
            tasks.register<PublishEventBridgeEventsTask>("badEvents") { eventBusName.set("bus"); fixtures.set(layout.projectDirectory.file("events.json")) }
            tasks.register<CreateKinesisStreamTask>("badStream") { streamName.set("stream"); shardCount.set(0) }
            tasks.register<CreateKmsKeyTask>("badKey") { aliasName.set("alias/aws/reserved") }
            tasks.register<FilterLogEventsTask>("badLogs") { logGroupName.set("/test"); maxEvents.set(0); outputFile.set(layout.buildDirectory.file("logs.json")) }
            """);
        String output = runner(project, "badParameters", "badSecrets", "badEvents", "badStream", "badKey", "badLogs", "--continue").buildAndFail().getOutput();
        for (String text : new String[] {"Duplicate", "Cannot read JSON fixture", "detail must be a JSON object", "shardCount must be positive", "AWS-managed aliases", "maxEvents must"}) assertTrue(output.contains(text), output);
        assertFalse(output.contains("hidden-fixture-value"), output);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }
}
