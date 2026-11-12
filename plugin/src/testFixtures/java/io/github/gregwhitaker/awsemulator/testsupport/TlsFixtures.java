package io.github.gregwhitaker.awsemulator.testsupport;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** Local TLS identities and endpoints, with no external network or certificate-generation dependency. */
public final class TlsFixtures {
    public static final String PASSWORD = "test-password";
    private TlsFixtures() {}

    public static KeyStore identity(Path directory, String name) throws Exception {
        return identity(directory, name, "ip:127.0.0.1,dns:localhost");
    }

    public static KeyStore identity(Path directory, String name, String subjectAlternativeNames) throws Exception {
        Path store = directory.resolve(name + ".p12");
        Path output = directory.resolve(name + ".log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-genkeypair", "-alias", "shared-alias", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
            "-dname", "CN=" + name, "-ext", "SAN=" + subjectAlternativeNames, "-ext", "bc=ca:true",
            "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD)
            .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Timed out generating test certificate");
        }
        if (process.exitValue() != 0) throw new IllegalStateException(Files.readString(output));
        return KeyStore.getInstance(store.toFile(), PASSWORD.toCharArray());
    }

    public static Certificate certificate(KeyStore identity) throws Exception { return identity.getCertificate("shared-alias"); }

    public static void pem(Path path, KeyStore identity) throws Exception {
        Files.writeString(path, "-----BEGIN CERTIFICATE-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate(identity).getEncoded())
            + "\n-----END CERTIFICATE-----\n");
    }

    public static void trustStore(Path path, String type, String password, KeyStore... identities) throws Exception {
        KeyStore store = KeyStore.getInstance(type);
        store.load(null, null);
        for (int i = 0; i < identities.length; i++) store.setCertificateEntry("root-" + i, certificate(identities[i]));
        Files.createDirectories(path.getParent());
        try (var output = Files.newOutputStream(path)) { store.store(output, password.toCharArray()); }
    }

    public static Endpoint server(KeyStore identity) throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(identity, PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", exchange -> {
            byte[] body = "trusted".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        return new Endpoint(server);
    }

    public record Endpoint(HttpsServer server) implements AutoCloseable {
        public String url() { return "https://127.0.0.1:" + server.getAddress().getPort() + "/"; }
        @Override public void close() { server.stop(0); }
    }
}
