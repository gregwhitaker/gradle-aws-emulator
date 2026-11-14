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

import org.gradle.api.GradleException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Build-scoped, byte-transparent forwarding. TLS still terminates and is validated at the emulator. */
public final class DockerTunnel implements AutoCloseable {
    private final ServerSocket listener;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final Semaphore capacity = new Semaphore(64);
    private final ExecutorService workers = Executors.newCachedThreadPool(action -> {
        Thread thread = new Thread(action, "aws-emulator-tunnel-io");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean closed;
    private volatile String diagnostic = "";
    private Thread acceptor;

    public DockerTunnel(int port) {
        try {
            listener = new ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            workers.shutdownNow();
            throw new GradleException("Cannot bind the AWS Emulator tunnel on local port " + port + ".", e);
        }
    }

    public int port() { return listener.getLocalPort(); }
    public String diagnostic() { return diagnostic; }

    @FunctionalInterface
    interface ConnectionProcess { Process open() throws IOException; }

    public void start(DockerClient docker, DockerClient.Container container) {
        start(() -> docker.openTunnel(container));
    }

    void start(ConnectionProcess connection) {
        if (acceptor != null || closed) throw new IllegalStateException("Tunnel is already started or closed");
        acceptor = new Thread(() -> {
            while (!closed) {
                try {
                    Socket socket = listener.accept();
                    try { socket.setTcpNoDelay(true); }
                    catch (IOException e) { socket.close(); throw e; }
                    if (!capacity.tryAcquire()) {
                        diagnostic = "AWS Emulator tunnel reached its limit of 64 simultaneous connections.";
                        socket.close();
                        continue;
                    }
                    Session session = new Session(socket);
                    sessions.add(session);
                    try { workers.execute(() -> session.forward(connection)); }
                    catch (RejectedExecutionException e) { session.close(); }
                } catch (IOException e) {
                    if (!closed) diagnostic = "Cannot accept an AWS Emulator tunnel connection: " + e.getMessage();
                }
            }
        }, "aws-emulator-tunnel-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private final class Session implements AutoCloseable {
        private final Socket socket;
        private final AtomicBoolean finished = new AtomicBoolean();
        private Process process;

        private Session(Socket socket) { this.socket = socket; }

        private void forward(ConnectionProcess connection) {
            try {
                if (closed || finished.get()) return;
                Process opened = connection.open();
                synchronized (this) {
                    if (closed || finished.get()) {
                        ProcessCommandRunner.terminate(opened);
                        return;
                    }
                    process = opened;
                }
                workers.execute(() -> stderr(opened.getErrorStream()));
                workers.execute(() -> {
                    try { copy(socket.getInputStream(), opened.getOutputStream()); }
                    catch (IOException ignored) { /* The peer or owning build closed the connection. */ }
                    finally { close(); }
                });
                copy(opened.getInputStream(), socket.getOutputStream());
            } catch (IOException e) {
                if (!closed && !finished.get()) diagnostic = "Docker tunnel could not connect: " + e.getMessage();
            } catch (RejectedExecutionException ignored) {
                // Shutdown won the race with connection setup.
            } finally {
                close();
            }
        }

        private void stderr(InputStream input) {
            byte[] buffer = new byte[1024];
            StringBuilder message = new StringBuilder();
            try {
                int length;
                while ((length = input.read(buffer)) >= 0) {
                    message.append(new String(buffer, 0, length, StandardCharsets.UTF_8));
                    if (message.length() > 4096) message.delete(0, message.length() - 4096);
                    if (!message.toString().isBlank()) diagnostic = message.toString().strip();
                }
            } catch (IOException ignored) { /* Process termination closes stderr. */ }
        }

        @Override public void close() {
            if (!finished.compareAndSet(false, true)) return;
            try { socket.close(); }
            catch (IOException ignored) { /* Already closed. */ }
            synchronized (this) {
                if (process != null) ProcessCommandRunner.terminate(process);
            }
            sessions.remove(this);
            capacity.release();
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16384];
        int length;
        while ((length = input.read(buffer)) >= 0) {
            output.write(buffer, 0, length);
            output.flush();
        }
    }

    @Override public void close() {
        closed = true;
        try { listener.close(); }
        catch (IOException ignored) { /* Already closed. */ }
        sessions.forEach(Session::close);
        workers.shutdownNow();
        try {
            if (acceptor != null) acceptor.join(1000);
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
