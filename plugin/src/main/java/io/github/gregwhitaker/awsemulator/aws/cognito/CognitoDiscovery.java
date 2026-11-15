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

package io.github.gregwhitaker.awsemulator.aws.cognito;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorTls;
import org.gradle.api.GradleException;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;

/** Reads issuer metadata only from the configured emulator, without following advertised remote URLs. */
final class CognitoDiscovery {
    private static final int MAX_BYTES = 65_536;

    private CognitoDiscovery() {}

    static String issuer(EmulatorConnection runtime, String poolId, int timeoutSeconds) {
        HttpsURLConnection connection = null;
        try {
            if (!poolId.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid Cognito user pool ID.");
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, EmulatorTls.trustManagers(runtime.trustStore()), null);
            connection = (HttpsURLConnection) URI.create(runtime.endpoint() + "/" + poolId
                + "/.well-known/openid-configuration").toURL().openConnection(Proxy.NO_PROXY);
            connection.setSSLSocketFactory(context.getSocketFactory());
            connection.setInstanceFollowRedirects(false);
            int timeout = (int) Math.min(timeoutSeconds * 1000L, Integer.MAX_VALUE);
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            if (connection.getResponseCode() != 200) {
                throw new IOException("Cognito discovery returned HTTP " + connection.getResponseCode() + ".");
            }
            byte[] body;
            try (var input = connection.getInputStream()) { body = input.readNBytes(MAX_BYTES + 1); }
            if (body.length == 0 || body.length > MAX_BYTES) throw new IOException("Invalid Cognito discovery document size.");
            var document = new ObjectMapper().readTree(body);
            var value = document.path("issuer");
            if (!value.isTextual()) throw new IOException("Cognito discovery has no issuer.");
            URI issuer = URI.create(value.textValue());
            if (!"https".equals(issuer.getScheme()) || issuer.getHost() == null || issuer.getUserInfo() != null
                || issuer.getQuery() != null || issuer.getFragment() != null || !("/" + poolId).equals(issuer.getRawPath())) {
                throw new IOException("Cognito discovery has an invalid issuer for the requested pool.");
            }
            return issuer.toString();
        } catch (Exception e) {
            throw new GradleException("Cannot read Cognito issuer from the emulator's local discovery document.", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
