package io.github.gregwhitaker.awsemulator.internal.runtime;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/** Loads only the emulator trust roots; never changes JVM-wide SSL configuration. */
public final class EmulatorTls {
    private EmulatorTls() {}

    public static TrustManager[] trustManagers(Path trustStore) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(trustStore)) { keys.load(input, null); }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keys);
        return factory.getTrustManagers();
    }
}
