package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class SharedRuntimeIntegrationTest {
    @TempDir Path project;

    @Test void sharesQueuesAcrossProcessesAndCleansUpAfterConsumersAndSetupFail() throws Exception {
        fixture();
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "verifySharedRuntime", "--parallel", "--max-workers=4").build().getOutput();
            assertTrue(output.contains("Processed order-1001 from the other module"), output);
            String endpoint = endpoint(output, "orders-api:run");
            for (String task : List.of("orders-worker:run", "orders-api:test", "orders-worker:test")) {
                assertEquals(endpoint, endpoint(output, task), output);
            }
            assertEquals(1, output.split("AWS Emulator ready at", -1).length - 1, output);
            assertTrue(output.contains(":local-aws:awsEmulatorResources_application_MANAGED_ordersQueue"), output);
            if (run > 0) assertTrue(output.contains("Reusing configuration cache"), output);
            assertCleanedUp();
            assertEquals("", containers("local-aws"));
        }

        String failure = runner(project, ":orders-worker:test", ":orders-worker:run", "--continue", "--parallel",
            "-PfailExample=true", "-PfailTest=true").buildAndFail().getOutput();
        assertTrue(failure.contains("Intentional worker failure"), failure);
        assertTrue(failure.contains("Processed order-1001 from the other module"), failure);
        assertTrue(Files.readString(project.resolve("orders-worker/build/test-results/test/TEST-example.orders.WorkerTest.xml"))
            .contains("Intentional worker test failure"));
        assertCleanedUp();
        assertEquals("", containers("local-aws"));

        Files.writeString(project.resolve("local-aws/build.gradle.kts"), """

            tasks.withType<CreateSqsQueueTask>().configureEach {
                doLast { throw GradleException("Intentional shared setup failure") }
            }
            """, StandardOpenOption.APPEND);
        String setup = runner(project, "verifySharedRuntime", "--continue", "--parallel").buildAndFail().getOutput();
        assertTrue(setup.contains("Intentional shared setup failure"), setup);
        assertFalse(setup.contains("AWS Emulator for :orders-"), setup);
        assertCleanedUp();
        assertEquals("", containers("local-aws"));
    }

    @Test void sharesDevelopmentAcrossInvocationsWhileManagedTestsStaySeparate() throws Exception {
        fixture();
        try {
            String first = runner(project, ":orders-worker:run", "-Pdevelopment=true").build().getOutput();
            String original = containers("local-aws");
            assertFalse(original.isBlank(), first);
            assertEquals(endpoint(first, "orders-api:run"), endpoint(first, "orders-worker:run"));
            String second = runner(project, ":orders-worker:run", "-Pdevelopment=true").build().getOutput();
            assertTrue(second.contains("Reusing configuration cache"), second);
            assertEquals(original, containers("local-aws"));
            assertEquals(endpoint(first, "orders-api:run"), endpoint(second, "orders-api:run"));

            String mixed = runner(project, "verifySharedRuntime", "-Pdevelopment=true", "--parallel").build().getOutput();
            assertEquals(endpoint(first, "orders-api:run"), endpoint(mixed, "orders-worker:run"));
            assertEquals(endpoint(mixed, "orders-api:test"), endpoint(mixed, "orders-worker:test"));
            assertNotEquals(endpoint(mixed, "orders-api:test"), endpoint(mixed, "orders-api:run"));
            assertEquals(original, containers("local-aws"));
            assertCleanedUp();
        } finally {
            runner(project, ":local-aws:stopAwsEmulator").build();
        }
        assertEquals("", containers("local-aws"));
        assertFalse(Files.exists(project.resolve("local-aws/.aws-emulator/development")));
    }

    private void fixture() throws Exception {
        Path source = Path.of(System.getProperty("multiProject.example"));
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(file);
                boolean generated = false;
                for (Path part : relative) if (List.of("build", ".gradle", ".aws-emulator").contains(part.toString())) generated = true;
                if (generated || file.getFileName().toString().equals("README.md")) continue;
                Path target = project.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        Path settings = project.resolve("settings.gradle.kts");
        Files.writeString(settings, Files.readString(settings).replace("    includeBuild(\"../..\")", ""));
    }

    private String endpoint(String output, String task) {
        var matcher = Pattern.compile("AWS Emulator for :" + Pattern.quote(task) + ": (https://127\\.0\\.0\\.1:\\d+)").matcher(output);
        assertTrue(matcher.find(), output);
        return matcher.group(1);
    }

    private void assertCleanedUp() throws Exception {
        try (var files = Files.list(project.resolve("local-aws/.aws-emulator"))) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith("managed-")));
        }
        for (String child : List.of("orders-api", "orders-worker")) {
            assertFalse(Files.exists(project.resolve(child + "/.aws-emulator")));
            assertEquals("", containers(child));
        }
    }

    private String containers(String child) throws Exception {
        return new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.resolve(child).toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking shared runtime cleanup");
    }
}
