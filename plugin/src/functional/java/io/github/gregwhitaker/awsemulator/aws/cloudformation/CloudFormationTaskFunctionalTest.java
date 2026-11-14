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

package io.github.gregwhitaker.awsemulator.aws.cloudformation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.isolatedRunner;
import static org.junit.jupiter.api.Assertions.*;

class CloudFormationTaskFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest @ValueSource(strings = {"groovy", "kotlin"})
    void bothDslsRegisterLazilyWithManagedCustomTasksAndDevelopmentConveniences(String language) throws Exception {
        settings();
        boolean kotlin = language.equals("kotlin");
        Files.writeString(project.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), kotlin ? """
            import io.github.gregwhitaker.awsemulator.aws.cloudformation.*
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("docker-must-not-run") }
            val stack = tasks.register<DeployCloudFormationStackTask>("stack") {
                stackName.set("example"); templateFile.set(layout.projectDirectory.file("missing.yml"))
                parameters.put("QueueName", "orders"); tags.put("env", "test")
                capabilities.add("CAPABILITY_NAMED_IAM")
            }
            check(stack.get().mode.get() == EmulatorMode.MANAGED)
            check(tasks.named<DeleteCloudFormationStackTask>("deleteCloudFormationStack").get().mode.get() == EmulatorMode.DEVELOPMENT)
            """ : """
            import io.github.gregwhitaker.awsemulator.aws.cloudformation.*
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('docker-must-not-run') }
            def stack = tasks.register('stack', DeployCloudFormationStackTask) {
                stackName.set('example'); templateFile.set(layout.projectDirectory.file('missing.yml'))
                parameters.put('QueueName', 'orders'); tags.put('env', 'test')
                capabilities.add('CAPABILITY_NAMED_IAM')
            }
            assert stack.get().mode.get() == EmulatorMode.MANAGED
            assert tasks.named('deleteCloudFormationStack').get().mode.get() == EmulatorMode.DEVELOPMENT
            """);
        String output = isolatedRunner(project, "tasks", "--all").build().getOutput();
        assertTrue(output.contains("AWS Emulator - CloudFormation"), output);
        assertTrue(isolatedRunner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test void validationRereadsChangedTemplateOnConfigurationCacheReuseWithoutDocker() throws Exception {
        fixture();
        Files.writeString(project.resolve("template.yml"), "Resources: {Queue: {Type: AWS::SQS::Queue}}");
        String first = runner(project, "validateCloudFormationTemplate", "--templateFile=template.yml").build().getOutput();
        assertTrue(first.contains("passed provisioning checks"), first);
        Files.writeString(project.resolve("template.yml"), "Resources: {Alarm: {Type: AWS::CloudWatch::Alarm}}");
        String rejected = runner(project, "validateCloudFormationTemplate", "--templateFile=template.yml").buildAndFail().getOutput();
        assertTrue(rejected.contains("Reusing configuration cache"), rejected);
        assertTrue(rejected.contains("Resources.Alarm (AWS::CloudWatch::Alarm)"), rejected);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test void deploymentRejectsTypesBeforeConnectionEvenWithExternalRuntimeAndFalseConditions() throws Exception {
        fixture();
        Files.writeString(project.resolve("template.yml"), """
            Conditions: {Never: {Fn::Equals: [one, two]}}
            Resources:
              Alarm: {Type: AWS::CloudWatch::Alarm, Condition: Never}
              Pool: {Type: AWS::Cognito::UserPool}
            """);
        for (String mode : new String[] {"MANAGED", "DEVELOPMENT"}) {
            String output = runner(project, "deployCloudFormationStack", "--stackName=example",
                "--templateFile=template.yml", "--mode=" + mode).buildAndFail().getOutput();
            assertTrue(output.contains("Resources.Alarm (AWS::CloudWatch::Alarm)"), output);
            assertTrue(output.contains("Resources.Pool (AWS::Cognito::UserPool)"), output);
            assertFalse(output.contains("Cannot run program"), output);
            assertFalse(Files.exists(project.resolve(".aws-emulator")));
        }
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.cloudformation.DeployCloudFormationStackTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { external { endpoint.set('https://localhost:1') } }
            tasks.register('deployCloudFormationStack', DeployCloudFormationStackTask)
            """);
        String external = runner(project, "deployCloudFormationStack", "--stackName=example",
            "--templateFile=template.yml").buildAndFail().getOutput();
        assertTrue(external.contains("Resources.Alarm (AWS::CloudWatch::Alarm)"), external);
        assertFalse(external.contains("external requires"), external);
    }

    @Test void checksSelectedRuntimeVersionBeforeDocker() throws Exception {
        fixture();
        Files.writeString(project.resolve("template.yml"), "Resources: {Queue: {Type: AWS::SQS::Queue}}");
        Files.writeString(project.resolve("build.gradle"), """
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator {
                dockerExecutable.set('docker-must-not-run')
                runtimes { register('older') { emulatorVersion.set('1.0.321') } }
            }
            """);
        String output = runner(project, "validateCloudFormationTemplate", "--templateFile=template.yml", "--runtime=older")
            .buildAndFail().getOutput();
        assertTrue(output.contains("configured emulatorVersion is 1.0.321"), output);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test void providerBackedGeneratedTemplateIsReadOnlyAtExecution() throws Exception {
        fixture();
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.cloudformation.*
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('docker-must-not-run') }
            abstract class GenerateTemplate extends DefaultTask {
                @OutputFile abstract RegularFileProperty getDestination()
                @TaskAction void generate() {
                    def target = destination.get().asFile
                    target.parentFile.mkdirs()
                    target.text = 'Resources: {Queue: {Type: AWS::SQS::Queue}}'
                }
            }
            def generate = tasks.register('generateTemplate', GenerateTemplate) {
                destination.set(layout.buildDirectory.file('generated/template.yml'))
            }
            tasks.named('validateCloudFormationTemplate', ValidateCloudFormationTemplateTask) {
                templateFile.set(generate.flatMap { it.destination })
            }
            """);
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "validateCloudFormationTemplate").build().getOutput();
            assertTrue(output.contains("passed provisioning checks"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
        }
        assertTrue(Files.exists(project.resolve("build/generated/template.yml")));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test void rejectsAnOutputPathThatWouldOverwriteTheTemplateBeforeConnecting() throws Exception {
        fixture();
        String template = "Resources: {Queue: {Type: AWS::SQS::Queue}}";
        Files.writeString(project.resolve("template.yml"), template);
        Files.writeString(project.resolve("build.gradle"), """
            tasks.named('deployCloudFormationStack') {
                stackName.set('example')
                templateFile.set(layout.projectDirectory.file('template.yml'))
                outputsFile.set(templateFile)
            }
            """, java.nio.file.StandardOpenOption.APPEND);
        String output = runner(project, "deployCloudFormationStack").buildAndFail().getOutput();
        assertTrue(output.contains("templateFile and outputsFile must differ"), output);
        assertEquals(template, Files.readString(project.resolve("template.yml")));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    private void settings() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'cloudformation-functional'");
    }

    private void fixture() throws Exception {
        settings();
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.cloudformation.DeployCloudFormationStackTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('docker-must-not-run') }
            tasks.register('deployCloudFormationStack', DeployCloudFormationStackTask)
            """);
    }
}
