package io.github.gregwhitaker.awsemulator.internal.runtime;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Verifies the emulator's certificate and IP address without changing JVM-wide TLS settings. */
public final class HttpsHealthProbe implements HealthProbe {
    @Override
    public boolean ready(int port, Path trustStore) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, EmulatorTls.trustManagers(trustStore), null);
        HttpsURLConnection connection = (HttpsURLConnection) URI.create("https://127.0.0.1:" + port + "/__admin/health")
            .toURL().openConnection(Proxy.NO_PROXY);
        connection.setSSLSocketFactory(context.getSocketFactory());
        connection.setConnectTimeout(500);
        connection.setReadTimeout(500);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) return false;
            try (InputStream input = connection.getInputStream()) {
                return new String(input.readNBytes(16), StandardCharsets.UTF_8).trim().equals("ok");
            }
        } finally {
            connection.disconnect();
        }
    }
}
