package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class EmulatorIntegrationTest {
    @TempDir Path project;

    @Test
    void managedTestsUseSignedHttpsShareOneRuntimeReuseConfigurationAndCleanUpAfterFailure() throws Exception {
        fixture(project);
        var first = runner(project, "test", "secondTest", "execProbe").build();
        assertTrue(first.getOutput().contains("AWS Emulator ready at"), first.getOutput());
        assertEquals(Files.readString(project.resolve("test-connection.txt")), Files.readString(project.resolve("second-connection.txt")));
        assertEquals(Files.readString(project.resolve("test-connection.txt")), Files.readString(project.resolve("exec-connection.txt")));
        assertCleanedUp(project, "test-connection.txt");
        assertCleanedUp(project, "second-connection.txt");
        var second = runner(project, "test", "secondTest", "execProbe").build();
        assertTrue(second.getOutput().contains("Reusing configuration cache"), second.getOutput());
        assertTrue(second.getOutput().contains("AWS Emulator ready at"), second.getOutput());
        assertCleanedUp(project, "test-connection.txt");
        var failed = runner(project, "test", "-PfailFixture=true").buildAndFail();
        assertTrue(failed.getOutput().contains("Intentional test failure"), failed.getOutput());
        assertCleanedUp(project, "test-connection.txt");
        assertEquals("", containers(project));
    }

    @Test
    void developmentLifecyclePersistsAcrossBuildsAndManagedCleanupLeavesItRunning() throws Exception {
        fixture(project);
        try {
            assertTrue(runner(project, "prepareAwsEmulator").build().getOutput().contains("image ready"));
            assertTrue(runner(project, "startAwsEmulator").build().getOutput().contains("development instance ready"));
            String original = containers(project);
            assertFalse(original.isBlank());
            assertTrue(runner(project, "startAwsEmulator").build().getOutput().contains("Reusing configuration cache"));
            assertEquals(original, containers(project));
            assertTrue(runner(project, "statusAwsEmulator").build().getOutput().contains("running, healthy"));
            assertTrue(runner(project, "logsAwsEmulator").build().getOutput().contains("emulator server information"));
            runner(project, "test").build();
            assertEquals(original, containers(project), "Managed cleanup must not stop the development instance");
            runner(project, "restartAwsEmulator").build();
            assertNotEquals(original, containers(project));
        } finally {
            runner(project, "stopAwsEmulator").build();
        }
        assertEquals("", containers(project));
        assertTrue(runner(project, "statusAwsEmulator").build().getOutput().contains("not running"));
        runner(project, "stopAwsEmulator").build();
    }

    @Test
    void parallelProjectsGetSeparateManagedRuntimes() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'parallel-fixture'\ninclude 'one', 'two'\n");
        for (String child : List.of("one", "two")) {
            Path directory = project.resolve(child);
            Files.createDirectories(directory);
            fixture(directory);
        }
        runner(project, ":one:test", ":two:test", "--parallel", "--max-workers=4").build();
        assertNotEquals(Files.readString(project.resolve("one/test-connection.txt")), Files.readString(project.resolve("two/test-connection.txt")));
        for (String child : List.of("one", "two")) {
            assertCleanedUp(project.resolve(child), "test-connection.txt");
            assertEquals("", containers(project.resolve(child)));
        }
    }

    private static void fixture(Path project) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'docker-fixture'\n");
        Files.writeString(project.resolve("build.gradle"), """
            plugins {
                id 'java'
                id 'io.github.gregwhitaker.aws-emulator'
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation platform('org.junit:junit-bom:5.11.4')
                testImplementation 'org.junit.jupiter:junit-jupiter'
                testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
                testImplementation platform('software.amazon.awssdk:bom:2.39.6')
                testImplementation 'software.amazon.awssdk:sts'
                testImplementation 'software.amazon.awssdk:url-connection-client'
            }
            def second = tasks.register('secondTest', Test) {
                testClassesDirs = sourceSets.test.output.classesDirs
                classpath = sourceSets.test.runtimeClasspath
                systemProperty 'fixture.output', layout.projectDirectory.file('second-connection.txt').asFile.absolutePath
            }
            tasks.withType(Test).configureEach {
                useJUnitPlatform()
                environment 'AWS_ACCESS_KEY_ID', 'not-real'
                environment 'AWS_SECRET_ACCESS_KEY', 'not-real'
                environment 'AWS_SESSION_TOKEN', 'not-real'
                environment 'AWS_PROFILE', 'production'
                systemProperty 'aws.accessKeyId', 'not-real'
                systemProperty 'aws.secretAccessKey', 'not-real'
                systemProperty 'fixture.fail', providers.gradleProperty('failFixture').getOrElse('false')
                testLogging { exceptionFormat = 'full' }
            }
            test { systemProperty 'fixture.output', layout.projectDirectory.file('test-connection.txt').asFile.absolutePath }
            def execProbe = tasks.register('execProbe', JavaExec) {
                classpath = sourceSets.main.runtimeClasspath
                mainClass = 'Probe'
                systemProperty 'fixture.output', layout.projectDirectory.file('exec-connection.txt').asFile.absolutePath
            }
            awsEmulator {
                port.set(0)
                useWith(tasks.named('test'))
                useWith(second)
                useWith(execProbe)
            }
            """);
        Path main = project.resolve("src/main/java/Probe.java");
        Files.createDirectories(main.getParent());
        Files.writeString(main, """
            import java.nio.file.*;
            import java.net.*;
            import java.net.http.*;
            public class Probe {
                public static void main(String[] args) throws Exception {
                    String endpoint = System.getProperty("aws.endpointUrl");
                    var response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(endpoint + "/__admin/health")).build(),
                        HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200 || !response.body().trim().equals("ok")) {
                        throw new IllegalStateException("Emulator health check failed");
                    }
                    Files.writeString(Path.of(System.getProperty("fixture.output")),
                        endpoint + "\\n" + System.getenv("AWS_SHARED_CREDENTIALS_FILE"));
                }
            }
            """);
        Path source = project.resolve("src/test/java/ProbeTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            import org.junit.jupiter.api.Test;
            import java.nio.file.*;
            import software.amazon.awssdk.services.sts.StsClient;
            import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
            import static org.junit.jupiter.api.Assertions.*;
            class ProbeTest {
                @Test void talksToEmulator() throws Exception {
                    assertNull(System.getenv("AWS_ACCESS_KEY_ID"));
                    assertNull(System.getenv("AWS_SECRET_ACCESS_KEY"));
                    assertNull(System.getenv("AWS_SESSION_TOKEN"));
                    assertNull(System.getenv("AWS_PROFILE"));
                    assertNull(System.getProperty("aws.accessKeyId"));
                    assertNull(System.getProperty("aws.secretAccessKey"));
                    Files.writeString(Path.of(System.getProperty("fixture.output")),
                        System.getProperty("aws.endpointUrl") + "\\n" + System.getenv("AWS_SHARED_CREDENTIALS_FILE"));
                    try (StsClient sts = StsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
                        assertEquals("000000000000", sts.getCallerIdentity().account());
                    }
                    assertFalse(Boolean.getBoolean("fixture.fail"), "Intentional test failure");
                }
            }
            """);
    }

    private static void assertCleanedUp(Path project, String output) throws Exception {
        String credentials = Files.readAllLines(project.resolve(output)).get(1);
        assertFalse(Files.exists(Path.of(credentials)), "Generated credentials should be removed with the managed instance");
    }

    private static String containers(Path project) throws java.io.IOException {
        return new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking container cleanup");
    }
}
