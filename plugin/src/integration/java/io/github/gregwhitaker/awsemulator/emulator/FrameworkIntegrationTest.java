package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import io.github.gregwhitaker.awsemulator.testsupport.FrameworkExamples;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class FrameworkIntegrationTest {
    @TempDir Path project;

    @ParameterizedTest @ValueSource(strings = {"micronaut", "ktor"})
    void sharesManagedRuntimeOnCacheReuseAndCleansUpAfterApplicationAndTestFailures(String framework) throws Exception {
        fixture(framework);
        for (int run = 0; run < 2; run++) {
            var result = runner(project, "test", "run").build();
            String output = result.getOutput();
            assertTrue(output.contains("Received from AWS Emulator: Hello from"), output);
            assertEquals(TaskOutcome.SUCCESS, result.task(":test").getOutcome());
            assertEquals(TaskOutcome.SUCCESS, result.task(":run").getOutcome());
            assertEquals(endpoint(output, "test"), endpoint(output, "run"));
            if (run == 1) assertTrue(output.contains("Reusing configuration cache"), output);
            assertNoManagedRuntime();
            assertEquals("", containers());
        }

        String applicationFailure = runner(project, "run", "-PfailExample=true").buildAndFail().getOutput();
        assertTrue(applicationFailure.contains("Intentional application failure"), applicationFailure);
        assertNoManagedRuntime();
        assertEquals("", containers());

        String testFailure = runner(project, "test", "-PfailExample=true").buildAndFail().getOutput();
        assertTrue(testFailure.contains("There were failing tests"), testFailure);
        try (var reports = Files.list(project.resolve("build/test-results/test"))) {
            boolean intentional = false;
            for (Path report : reports.filter(path -> path.toString().endsWith(".xml")).toList()) {
                intentional |= Files.readString(report).contains("Intentional test failure");
            }
            assertTrue(intentional, "The test must reach its SDK assertion before the deliberate failure");
        }
        assertNoManagedRuntime();
        assertEquals("", containers());
    }

    @ParameterizedTest @ValueSource(strings = {"micronaut", "ktor"})
    void reusesDevelopmentRuntimeAndKeepsTestsIsolatedUntilExplicitStop(String framework) throws Exception {
        fixture(framework);
        try {
            String first = runner(project, "run", "-Pdevelopment=true").build().getOutput();
            String container = containers();
            assertFalse(container.isBlank(), first);
            String second = runner(project, "run", "-Pdevelopment=true").build().getOutput();
            assertTrue(second.contains("Reusing configuration cache"), second);
            assertEquals(endpoint(first, "run"), endpoint(second, "run"));
            assertEquals(container, containers());

            String tests = runner(project, "test", "-Pdevelopment=true").build().getOutput();
            assertNotEquals(endpoint(first, "run"), endpoint(tests, "test"));
            assertNoManagedRuntime();
            assertEquals(container, containers(), "Managed test cleanup must preserve the development instance");

            String failure = runner(project, "run", "-Pdevelopment=true", "-PfailExample=true").buildAndFail().getOutput();
            assertTrue(failure.contains("Intentional application failure"), failure);
            assertEquals(container, containers());
        } finally {
            runner(project, "stopAwsEmulator").build();
        }
        assertEquals("", containers());
        assertFalse(Files.exists(project.resolve(".aws-emulator/development")));
    }

    private void fixture(String framework) throws Exception {
        FrameworkExamples.copyTo(project, framework);
        Files.writeString(project.resolve("build.gradle.kts"), """

            // Invalid inherited settings must be replaced for both kinds of framework consumers.
            tasks.withType<JavaExec>().configureEach {
                environment("AWS_ACCESS_KEY_ID", "not-a-real-key")
                environment("AWS_SECRET_ACCESS_KEY", "not-a-real-secret")
                environment("AWS_PROFILE", "missing-profile")
                environment("AWS_ENDPOINT_URL_SQS", "https://127.0.0.1:1")
                systemProperty("aws.endpointUrl.sqs", "https://127.0.0.1:1")
                systemProperty("aws.accessKeyId", "not-a-real-key")
            }
            tasks.withType<Test>().configureEach {
                environment("AWS_ACCESS_KEY_ID", "not-a-real-key")
                environment("AWS_SECRET_ACCESS_KEY", "not-a-real-secret")
                environment("AWS_PROFILE", "missing-profile")
                environment("AWS_ENDPOINT_URL_SQS", "https://127.0.0.1:1")
                systemProperty("aws.endpointUrl.sqs", "https://127.0.0.1:1")
                systemProperty("aws.accessKeyId", "not-a-real-key")
            }
            """, StandardOpenOption.APPEND);
    }

    private String endpoint(String output, String task) {
        var matcher = Pattern.compile("AWS Emulator for :" + task + ": (https://127\\.0\\.0\\.1:\\d+)").matcher(output);
        assertTrue(matcher.find(), output);
        return matcher.group(1);
    }

    private void assertNoManagedRuntime() throws Exception {
        try (var paths = Files.list(project.resolve(".aws-emulator"))) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith("managed-")),
                "Managed container credentials and trust stores must be removed");
        }
    }

    private String containers() throws Exception {
        return new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking framework runtime cleanup");
    }
}
