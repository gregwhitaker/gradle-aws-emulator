package io.github.gregwhitaker.awsemulator.aws.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;

import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class SesTasksIntegrationTest {
    @TempDir Path project;

    @Test void managedSdkExampleSendsOnCacheReuseAndCleansUpAfterConsumerFailure() throws Exception {
        fixture();
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "integrationTest").build().getOutput();
            assertTrue(output.contains("SES accepted message"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
            var insights = new ObjectMapper().readTree(project.resolve("build/ses/insights.json").toFile());
            assertEquals("Local receipt", insights.path("subject").asText());
            assertNoContainers();
        }
        String failure = runner(project, "integrationTest", "-PfailExample=true").buildAndFail().getOutput();
        assertTrue(failure.contains("There were failing tests"), failure);
        assertNoContainers();
    }

    @Test void developmentResourcesAreReusedConflictsRequireOverwriteAndDeletesAreRepeatable() throws Exception {
        fixture();
        try {
            runner(project, "provisionSes", "-Pdevelopment=true").build();
            try (var ses = new AwsClientFactory(connection(), 30).sesV2()) {
                String reused = runner(project, "provisionSes", "-Pdevelopment=true").build().getOutput();
                assertTrue(reused.contains("Reusing configuration cache"), reused);
                assertEquals(1, ses.listEmailIdentities(r -> r.pageSize(1000)).emailIdentities().size());
                assertEquals(1, ses.listConfigurationSets(r -> r.pageSize(1000)).configurationSets().size());
                assertEquals(1, ses.getConfigurationSetEventDestinations(r -> r.configurationSetName("example-mail")).eventDestinations().size());
                Path template = project.resolve("fixtures/welcome.txt");
                Files.writeString(template, "Changed private template body");
                String conflict = runner(project, "putWelcomeTemplate", "-Pdevelopment=true").buildAndFail().getOutput();
                assertTrue(conflict.contains("overwrite=true"), conflict);
                assertFalse(conflict.contains("Changed private template body"), conflict);
                runner(project, "putWelcomeTemplate", "-Pdevelopment=true", "-Poverwrite=true").build();
                assertEquals("Changed private template body", ses.getEmailTemplate(r -> r.templateName("example-welcome")).templateContent().text());
                // Keep the send task's provisioning dependency consistent with the replaced template.
                runner(project, "sendFixtureEmail", "-Pdevelopment=true").build();
                String first = Files.readString(messageIdFile()).trim();
                String secondRun = runner(project, "sendFixtureEmail", "-Pdevelopment=true").build().getOutput();
                assertTrue(secondRun.contains("Reusing configuration cache"), secondRun);
                String second = Files.readString(messageIdFile()).trim();
                assertNotEquals(first, second);
                assertEquals("Local receipt", ses.getMessageInsights(r -> r.messageId(second)).subject());
                String listed = runner(project, "listSesEmailIdentities", "listSesConfigurationSets").build().getOutput();
                assertTrue(listed.contains("sender@example.test"), listed);
                assertTrue(listed.contains("example-mail"), listed);
                runner(project, "getSesMessageInsights", "--messageId=" + second).build();
                assertTrue(Files.readString(project.resolve("build/aws-emulator/ses/getSesMessageInsights/insights.json")).contains(second));
                for (int run = 0; run < 2; run++) {
                    runner(project, "deleteSesEmailIdentity", "--emailIdentity=sender@example.test",
                        "deleteSesEmailTemplate", "--templateName=example-welcome",
                        "deleteSesConfigurationSet", "--configurationSetName=example-mail").build();
                }
                assertTrue(ses.listEmailIdentities(r -> r.pageSize(1000)).emailIdentities().isEmpty());
                assertTrue(ses.listConfigurationSets(r -> r.pageSize(1000)).configurationSets().isEmpty());
                assertThrows(NotFoundException.class, () -> ses.getEmailTemplate(r -> r.templateName("example-welcome")));
            }
        } finally { runner(project, "stopAwsEmulator").build(); }
        assertNoContainers();
    }

    @Test void rejectedSendCleansUpManagedRuntimeAndDoesNotExposeEmailContent() throws Exception {
        fixture();
        Files.writeString(project.resolve("build.gradle.kts"), """
            tasks.register<SendSesEmailTask>("rejectedEmail") {
                fromAddress.set("unknown@example.test"); toAddresses.add("success@simulator.amazonses.com")
                subject.set("private rejected subject"); textBody.set("private rejected body")
            }
            """, StandardOpenOption.APPEND);
        String output = runner(project, "rejectedEmail").buildAndFail().getOutput();
        assertTrue(output.contains("MessageRejected"), output);
        assertFalse(output.contains("private rejected subject"), output);
        assertFalse(output.contains("private rejected body"), output);
        assertNoContainers();
    }

    private Path messageIdFile() { return project.resolve("build/aws-emulator/ses/sendFixtureEmail/message-id.txt"); }

    private void fixture() throws Exception {
        Path source = Path.of(System.getProperty("ses.example"));
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(file);
                if (relative.startsWith("build") || relative.startsWith(".gradle") || relative.startsWith(".aws-emulator")
                    || relative.toString().equals("settings.gradle.kts")) continue;
                Path target = project.resolve(relative);
                Files.createDirectories(target.getParent()); Files.copy(file, target);
            }
        }
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"ses-integration\"\n");
    }

    private EmulatorConnection connection() throws Exception {
        try (var paths = Files.list(project.resolve(".aws-emulator/development/.aws/emulator"))) {
            Path path = paths.filter(Files::isDirectory).findFirst().orElseThrow();
            return new EmulatorConnection(Integer.parseInt(path.getFileName().toString()), "us-east-1", path);
        }
    }

    private void assertNoContainers() throws Exception {
        assertEquals("", new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking SES cleanup"));
    }
}
