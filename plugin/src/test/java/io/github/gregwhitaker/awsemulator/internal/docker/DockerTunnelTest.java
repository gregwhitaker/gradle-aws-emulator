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

package io.github.gregwhitaker.awsemulator.internal.docker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class DockerTunnelTest {
    @Test
    void forwardsBinaryStreamsIndependentlyAndClosesProcessesAndListener() throws Exception {
        var processes = new CopyOnWriteArrayList<Process>();
        int port;
        try (var tunnel = new DockerTunnel(0)) {
            port = tunnel.port();
            tunnel.start(() -> {
                Process process = echo();
                processes.add(process);
                return process;
            });
            try (var first = new Socket("127.0.0.1", port); var second = new Socket("127.0.0.1", port)) {
                first.setSoTimeout(10000);
                second.setSoTimeout(10000);
                byte[] payload = new byte[8192];
                for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
                first.getOutputStream().write(payload);
                second.getOutputStream().write(new byte[] {4, 0, -1, 10});
                assertArrayEquals(payload, first.getInputStream().readNBytes(payload.length));
                assertArrayEquals(new byte[] {4, 0, -1, 10}, second.getInputStream().readNBytes(4));
                assertEquals(2, processes.size());
                // Closing the owner while consumers remain connected must unblock all stream pumps.
                tunnel.close();
                assertEquals(-1, first.getInputStream().read());
                assertEquals(-1, second.getInputStream().read());
            }
        }
        for (Process process : processes) assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        try (var socket = new ServerSocket(port, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, socket.getLocalPort());
        }
    }

    @Test
    void reportsProcessStartupFailureWithoutLeavingTheClientBlocked() throws Exception {
        try (var tunnel = new DockerTunnel(0)) {
            tunnel.start(() -> { throw new IOException("fixture Docker connection failed"); });
            try (var socket = new Socket("127.0.0.1", tunnel.port())) {
                socket.setSoTimeout(5000);
                assertEquals(-1, socket.getInputStream().read());
                assertTrue(tunnel.diagnostic().contains("fixture Docker connection failed"));
            }
        }
    }

    @Test
    void closingBeforeStartupReleasesTheReservedPort() throws Exception {
        var tunnel = new DockerTunnel(0);
        int port = tunnel.port();
        tunnel.close();
        assertThrows(IllegalStateException.class, () -> tunnel.start(DockerTunnelTest::echo));
        try (var socket = new ServerSocket(port, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, socket.getLocalPort());
        }
    }

    @Test
    void shutdownTerminatesAProcessCreatedWhileItsConnectionWasClosing() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Process process = echo();
        try (var tunnel = new DockerTunnel(0)) {
            tunnel.start(() -> {
                started.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Simulate ProcessBuilder completing during shutdown. */ }
                return process;
            });
            try (var socket = new Socket("127.0.0.1", tunnel.port())) {
                socket.setSoTimeout(5000);
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var closing = CompletableFuture.runAsync(tunnel::close);
                assertEquals(-1, socket.getInputStream().read());
                release.countDown();
                closing.get(10, TimeUnit.SECONDS);
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        } finally {
            release.countDown();
            process.destroyForcibly();
        }
    }

    private static Process echo() throws IOException {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        try {
            String classes = Path.of(Echo.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            return new ProcessCommandRunner().start(List.of(java, "-cp", classes, Echo.class.getName()));
        } catch (java.net.URISyntaxException e) { throw new IOException(e); }
    }

    public static class Echo {
        public static void main(String[] args) throws Exception {
            System.err.println("Diagnostic text must never enter the binary stream");
            byte[] bytes = new byte[4096];
            int length;
            while ((length = System.in.read(bytes)) >= 0) {
                System.out.write(bytes, 0, length);
                System.out.flush();
            }
        }
    }
}
