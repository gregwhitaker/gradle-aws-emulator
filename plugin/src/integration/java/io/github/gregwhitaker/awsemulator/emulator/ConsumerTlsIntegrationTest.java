package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.testsupport.TlsFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerTlsIntegrationTest {
    @TempDir Path project;

    @Test
    void testAndJavaExecKeepDefaultAndCustomTrustAcrossCachedManagedAndDevelopmentRuns() throws Exception {
        var firstIdentity = TlsFixtures.identity(project, "first");
        var secondIdentity = TlsFixtures.identity(project, "second");
        Path firstStore = project.resolve("first trust.p12");
        Path secondStore = project.resolve("second trust.jks");
        TlsFixtures.trustStore(firstStore, "PKCS12", TlsFixtures.PASSWORD, firstIdentity);
        TlsFixtures.trustStore(secondStore, "JKS", TlsFixtures.PASSWORD, secondIdentity);
        byte[] original = Files.readAllBytes(firstStore);
        try (var first = TlsFixtures.server(firstIdentity); var second = TlsFixtures.server(secondIdentity)) {
            fixture(first.url(), second.url());
            for (int run = 0; run < 2; run++) {
                var result = runner(project, "test", "probe", "environmentProbe").build();
                if (run > 0) assertTrue(result.getOutput().contains("Reusing configuration cache"), result.getOutput());
                for (String output : new String[] {"test", "probe", "environmentProbe"}) {
                    assertFalse(Files.exists(Path.of(Files.readString(project.resolve(output + ".txt")))));
                }
            }
            try {
                for (int run = 0; run < 2; run++) {
                    var result = runner(project, "probe", "-Pdevelopment=true").build();
                    if (run > 0) assertTrue(result.getOutput().contains("Reusing configuration cache"), result.getOutput());
                    assertTrue(Files.exists(Path.of(Files.readString(project.resolve("probe.txt")))));
                }
            } finally {
                runner(project, "stopAwsEmulator").build();
            }
            assertFalse(Files.exists(Path.of(Files.readString(project.resolve("probe.txt")))));
            var failed = runner(project, "test", "-PbadTrust=true").buildAndFail();
            assertTrue(failed.getOutput().contains("Cannot prepare AWS Emulator consumer TLS trust"), failed.getOutput());
            try (var files = Files.list(project.resolve(".aws-emulator"))) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("managed-")));
            }
        }
        assertArrayEquals(original, Files.readAllBytes(firstStore));
    }

    private void fixture(String first, String second) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'consumer-tls'\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            import org.gradle.process.CommandLineArgumentProvider
            plugins { id 'java'; id 'io.github.gregwhitaker.aws-emulator' }
            repositories { mavenCentral() }
            dependencies {
                testImplementation platform('org.junit:junit-bom:5.11.4')
                testImplementation 'org.junit.jupiter:junit-jupiter'
                testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
            }
            class TrustArguments implements CommandLineArgumentProvider, Serializable {
                Iterable<String> asArguments() {
                    ['-Djavax.net.ssl.trustStorePassword=test-password', '-Djavax.net.ssl.trustStoreProvider=SUN',
                     '-Dfixture.provider=preserved']
                }
            }
            def probe = tasks.register('probe', JavaExec) {
                classpath = sourceSets.main.runtimeClasspath
                mainClass = 'TrustProbe'
                systemProperty 'fixture.expected', '%s'
                systemProperty 'fixture.rejected', '%s'
                systemProperty 'fixture.output', 'probe.txt'
                jvmArgs '-Djavax.net.ssl.trustStore=first trust.p12', '-Djavax.net.ssl.trustStoreType=PKCS12'
                jvmArgumentProviders.add(new TrustArguments())
                environment 'JAVA_TOOL_OPTIONS', '-Dfixture.environment="retained value"'
            }
            def environmentProbe = tasks.register('environmentProbe', JavaExec) {
                classpath = sourceSets.main.runtimeClasspath
                mainClass = 'TrustProbe'
                systemProperty 'fixture.expected', '%s'
                systemProperty 'fixture.rejected', '%s'
                systemProperty 'fixture.output', 'environmentProbe.txt'
                systemProperty 'javax.net.ssl.trustStore', 'overridden-missing.jks'
                environment 'JAVA_TOOL_OPTIONS', '-Djavax.net.ssl.trustStore="also-missing.p12" -Dfixture.environment="retained value"'
                environment '_JAVA_OPTIONS', '-Djavax.net.ssl.trustStore="second trust.jks" -Djavax.net.ssl.trustStorePassword=test-password -Djavax.net.ssl.trustStoreType=JKS -Dfixture.provider=preserved'
            }
            test {
                useJUnitPlatform()
                systemProperty 'fixture.expected', '%s'
                systemProperty 'fixture.rejected', '%s'
                systemProperty 'fixture.output', 'test.txt'
                systemProperty 'fixture.provider', 'preserved'
                environment 'JAVA_TOOL_OPTIONS', '-Dfixture.environment="retained value"'
                systemProperty 'javax.net.ssl.trustStore', 'second trust.jks'
                systemProperty 'javax.net.ssl.trustStoreType', 'JKS'
                systemProperty 'javax.net.ssl.trustStoreProvider', 'SUN'
                systemProperty 'javax.net.ssl.trustStorePassword', providers.gradleProperty('badTrust').isPresent() ? 'bad' : 'test-password'
                testLogging { exceptionFormat = 'full' }
            }
            awsEmulator {
                port.set(0)
                def mode = providers.gradleProperty('development').map { EmulatorMode.DEVELOPMENT }.orElse(EmulatorMode.MANAGED)
                useWith(tasks.named('test'), mode)
                useWith(probe, mode)
                useWith(environmentProbe, mode)
            }
            """.formatted(first, second, second, first, second, first));
        Path source = project.resolve("src/main/java/TrustProbe.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            import java.net.*;
            import java.net.http.*;
            import java.nio.file.*;
            import java.security.*;
            import javax.net.ssl.SSLHandshakeException;
            public class TrustProbe {
                public static void main(String[] args) throws Exception {
                    HttpClient client = HttpClient.newHttpClient();
                    String health = client.send(HttpRequest.newBuilder(URI.create(System.getProperty("aws.endpointUrl")
                        + "/__admin/health")).build(), HttpResponse.BodyHandlers.ofString()).body();
                    if (!health.trim().equals("ok")) throw new AssertionError("emulator TLS");
                    if (!client.send(HttpRequest.newBuilder(URI.create(System.getProperty("fixture.expected"))).build(),
                            HttpResponse.BodyHandlers.ofString()).body().equals("trusted")) throw new AssertionError("custom TLS");
                    try {
                        client.send(HttpRequest.newBuilder(URI.create(System.getProperty("fixture.rejected"))).build(),
                            HttpResponse.BodyHandlers.ofString());
                        throw new AssertionError("Another consumer's certificate was trusted");
                    } catch (SSLHandshakeException expected) { }
                    Path trust = Path.of(System.getProperty("javax.net.ssl.trustStore"));
                    KeyStore merged = KeyStore.getInstance(trust.toFile(), System.getProperty("javax.net.ssl.trustStorePassword").toCharArray());
                    Path defaults = Path.of(System.getProperty("java.home"), "lib/security/jssecacerts");
                    if (!Files.exists(defaults)) defaults = defaults.resolveSibling("cacerts");
                    KeyStore roots = KeyStore.getInstance(defaults.toFile(), (char[]) null);
                    var aliases = roots.aliases();
                    while (aliases.hasMoreElements()) {
                        if (merged.getCertificateAlias(roots.getCertificate(aliases.nextElement())) == null) {
                            throw new AssertionError("Missing default trust root");
                        }
                    }
                    if (!"preserved".equals(System.getProperty("fixture.provider"))) throw new AssertionError("provider arguments");
                    if (!"retained value".equals(System.getProperty("fixture.environment"))) throw new AssertionError("environment arguments");
                    Files.writeString(Path.of(System.getProperty("fixture.output")), trust.toString());
                }
            }
            """);
        Path test = project.resolve("src/test/java/TrustProbeTest.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, """
            import org.junit.jupiter.api.Test;
            class TrustProbeTest {
                @Test void connectsWithMergedTrust() throws Exception { TrustProbe.main(new String[0]); }
            }
            """);
    }
}
