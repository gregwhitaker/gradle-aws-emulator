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

package io.github.gregwhitaker.awsemulator.testcontainers;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.gregwhitaker.awsemulator.runtime.EmulatorCredentials;
import io.github.gregwhitaker.awsemulator.runtime.EmulatorHealth;
import io.github.gregwhitaker.awsemulator.runtime.EmulatorImage;
import io.github.gregwhitaker.awsemulator.runtime.EmulatorTls;
import io.github.gregwhitaker.awsemulator.testcontainers.internal.EmulatorImages;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A disposable AWS Emulator with generated credentials and verified HTTPS.
 * Use with Testcontainers' JUnit extension or call {@link #start()} and {@link #stop()} directly.
 * SDK clients must use this container's endpoint, credentials, region, and HTTP client builder.
 * Connection files are copied through Docker's API and deleted on stop; host bind mounts are not required.
 * This class does not modify JVM system properties, default TLS settings, or AWS credential providers.
 */
public class AwsEmulatorContainer extends GenericContainer<AwsEmulatorContainer> {
    private static final String CONNECTION_PATH = "/home/emulator/.aws/emulator/" + EmulatorImage.PORT + "/";
    private String region = "us-east-1";
    private boolean telemetryEnabled;
    private Duration connectionTimeout = Duration.ofSeconds(120);
    private Path connectionDirectory;
    private URI endpoint;
    private AwsCredentialsProvider credentials;
    private TrustManager[] trustManagers;

    /** Builds the pinned {@link EmulatorImage#DEFAULT_VERSION} image on first startup. */
    public AwsEmulatorContainer() { this(EmulatorImage.DEFAULT_VERSION); }

    /** Builds an image for a pinned emulator release, such as {@code 1.0.322}. */
    public AwsEmulatorContainer(String emulatorVersion) { this(EmulatorImages.forVersion(emulatorVersion)); }

    /** Uses an existing compatible image whose entrypoint starts AWS Emulator. */
    public AwsEmulatorContainer(DockerImageName image) {
        super(image);
        initialize();
    }

    private AwsEmulatorContainer(Future<String> image) {
        super(image);
        initialize();
    }

    private void initialize() {
        withExposedPorts(EmulatorImage.PORT);
        withEnv("HOME", "/home/emulator");
        // Generated credentials and certificates belong to this disposable instance, not an image layer.
        withTmpFs(Map.of("/home/emulator", "rw,size=16m,mode=0700,uid=1000,gid=1000"));
        withCreateContainerCmdModifier(command -> command.getHostConfig()
            .withMemory(2L * 1024 * 1024 * 1024).withMemorySwap(2L * 1024 * 1024 * 1024));
        waitingFor(new AbstractWaitStrategy() {
            @Override
            protected void waitUntilReady() { prepareConnection(startupTimeout); }
        });
        withStartupTimeout(Duration.ofSeconds(120));
    }

    /** Sets the SDK region. Defaults to {@code us-east-1}; configure before starting. */
    public AwsEmulatorContainer withRegion(String value) {
        requireNotStarted();
        if (value == null || !value.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("AWS Emulator region must contain lowercase letters, numbers, or hyphens.");
        }
        region = value;
        return this;
    }

    /** Enables or disables upstream usage telemetry. Defaults to disabled. */
    public AwsEmulatorContainer withTelemetryEnabled(boolean enabled) {
        requireNotStarted();
        telemetryEnabled = enabled;
        return this;
    }

    /** Sets the connection readiness timeout. Defaults to 120 seconds, excluding image preparation. */
    @Override
    public AwsEmulatorContainer withStartupTimeout(Duration timeout) {
        requireNotStarted();
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("AWS Emulator startup timeout must be positive.");
        }
        connectionTimeout = timeout;
        return super.withStartupTimeout(timeout);
    }

    /** Persistent container reuse is unsupported; each start after stop gets fresh AWS state. */
    @Override
    public AwsEmulatorContainer withReuse(boolean reuse) {
        if (reuse) throw new IllegalArgumentException("AWS Emulator containers do not support persistent reuse.");
        return super.withReuse(false);
    }

    @Override
    protected void configure() {
        withCommand("--port", Integer.toString(EmulatorImage.PORT), "--auth", "on",
            "--telemetry", telemetryEnabled ? "on" : "off");
    }

    @Override
    public void start() {
        try { super.start(); }
        catch (RuntimeException | Error failure) {
            try { stop(); }
            catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    @Override
    protected void containerIsStarted(InspectContainerResponse info) {
        // A caller may replace the wait strategy; connection validation is still mandatory.
        if (endpoint == null) prepareConnection(connectionTimeout);
    }

    private void prepareConnection(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Exception lastFailure = null;
        try {
            if (connectionDirectory == null) connectionDirectory = Files.createTempDirectory("aws-emulator-");
            URI address = endpoint(getHost(), getMappedPort(EmulatorImage.PORT));
            do {
                if (!isRunning()) throw new ContainerLaunchException("AWS Emulator exited before becoming ready.");
                try {
                    for (String name : new String[] {"emulator_credentials", "ca.pem", "truststore.p12"}) {
                        Path file = connectionDirectory.resolve(name);
                        copyConnectionFile(name, file, Math.max(1, Math.min(5000,
                            TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
                    }
                    var parsedCredentials = EmulatorCredentials.read(connectionDirectory.resolve("emulator_credentials"));
                    Path trustStore = connectionDirectory.resolve("truststore.p12");
                    if (EmulatorHealth.ready(address.toString(), trustStore)) {
                        trustManagers = EmulatorTls.trustManagers(trustStore);
                        credentials = StaticCredentialsProvider.create(parsedCredentials);
                        endpoint = address;
                        return;
                    }
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    lastFailure = e;
                }
                Thread.sleep(200);
            } while (System.nanoTime() < deadline);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContainerLaunchException("Interrupted while waiting for AWS Emulator.", e);
        } catch (IOException e) {
            throw new ContainerLaunchException("Cannot create AWS Emulator connection directory.", e);
        }
        throw new ContainerLaunchException("AWS Emulator did not become ready with valid credentials and verified HTTPS. "
            + "The Docker host must be reachable and covered by the emulator certificate.", lastFailure);
    }

    private void copyConnectionFile(String name, Path file, long timeoutMillis) throws IOException, InterruptedException {
        // Docker's archive API cannot read tmpfs contents on all engines. Stream bytes from exec
        // without converting credentials to text or passing them to Testcontainers' output logging.
        var command = getDockerClient().execCreateCmd(getContainerId()).withAttachStdout(true).withAttachStderr(true)
            .withCmd("cat", CONNECTION_PATH + name).exec();
        try (var output = Files.newOutputStream(file);
             var callback = new ResultCallback.Adapter<Frame>() {
                 @Override
                 public void onNext(Frame frame) {
                     if (frame.getStreamType() == StreamType.STDOUT) {
                         try { output.write(frame.getPayload()); }
                         catch (IOException e) { onError(e); }
                     }
                 }
             }) {
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
            getDockerClient().execStartCmd(command.getId()).exec(callback);
            if (!callback.awaitCompletion(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new IOException("Timed out copying AWS Emulator connection files.");
            }
            Long status = getDockerClient().inspectExecCmd(command.getId()).exec().getExitCodeLong();
            if (status == null || status != 0) throw new IOException("AWS Emulator connection files are not available yet.");
        }
    }

    static URI endpoint(String host, int port) {
        try { return new URI("https", null, host, port, null, null, null); }
        catch (URISyntaxException e) { throw new IllegalArgumentException("Invalid Docker host.", e); }
    }

    /** Returns the mapped HTTPS endpoint after startup. */
    public URI getEndpoint() { requireReady(); return endpoint; }

    /** Returns the selected SDK region, including before startup. */
    public String getRegion() { return region; }

    /** Returns only the credentials generated by this emulator, without provider-chain fallback. */
    public AwsCredentialsProvider getCredentialsProvider() { requireReady(); return credentials; }

    /** Returns the copied credentials file, valid until {@link #stop()}. */
    public Path getCredentialsFile() { requireReady(); return connectionDirectory.resolve("emulator_credentials"); }

    /** Returns the copied PEM CA certificate, valid until {@link #stop()}. */
    public Path getCertificateFile() { requireReady(); return connectionDirectory.resolve("ca.pem"); }

    /**
     * Returns a new AWS SDK v2 synchronous HTTP client builder trusting this emulator's certificate.
     * Certificate and hostname verification remain enabled. The SDK client owns the built HTTP client.
     */
    public UrlConnectionHttpClient.Builder getHttpClientBuilder() {
        requireReady();
        TrustManager[] trust = trustManagers.clone();
        return UrlConnectionHttpClient.builder().tlsTrustManagersProvider(() -> trust.clone())
            .connectionTimeout(Duration.ofSeconds(20)).socketTimeout(Duration.ofSeconds(20))
            .proxyConfiguration(proxy -> proxy.useSystemPropertyValues(false).useEnvironmentVariablesValues(false));
    }

    /** Stops this container and deletes its copied connection files, including after startup failure. */
    @Override
    public void stop() {
        try { super.stop(); }
        finally {
            endpoint = null;
            credentials = null;
            trustManagers = null;
            if (connectionDirectory != null) {
                try (var paths = Files.walk(connectionDirectory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                    connectionDirectory = null;
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot delete AWS Emulator connection files.", e);
                }
            }
        }
    }

    private void requireReady() {
        if (endpoint == null) throw new IllegalStateException("Start the AWS Emulator container before reading connection settings.");
    }

    private void requireNotStarted() {
        if (getContainerId() != null) throw new IllegalStateException("Configure AWS Emulator before starting it.");
    }
}
