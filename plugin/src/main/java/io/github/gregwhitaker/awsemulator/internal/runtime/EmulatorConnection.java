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

package io.github.gregwhitaker.awsemulator.internal.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/** Runtime connection paths, never the generated credential values. */
public record EmulatorConnection(String endpoint, String region, Path directory, Path credentials, Path certificate) {
    public EmulatorConnection(int port, String region, Path directory) {
        this("https://127.0.0.1:" + port, region, directory,
            directory.resolve("emulator_credentials"), directory.resolve("ca.pem"));
    }

    public int port() {
        int port = java.net.URI.create(endpoint).getPort();
        return port < 0 ? 443 : port;
    }
    static final Set<String> CREDENTIAL_ENVIRONMENT = Set.of(
        "AWS_ACCESS_KEY_ID", "AWS_ACCESS_KEY", "AWS_SECRET_ACCESS_KEY", "AWS_SECRET_KEY",
        "AWS_SESSION_TOKEN", "AWS_SECURITY_TOKEN", "AWS_PROFILE", "AWS_DEFAULT_PROFILE", "AWS_ROLE_ARN",
        "AWS_ROLE_SESSION_NAME", "AWS_WEB_IDENTITY_TOKEN_FILE", "AWS_CREDENTIAL_FILE",
        "AWS_IGNORE_CONFIGURED_ENDPOINT_URLS");

    public Path trustStore() { return directory.resolve("truststore.p12"); }

    public boolean filesExist() {
        return Files.isRegularFile(credentials()) && Files.isRegularFile(trustStore())
            && Files.isRegularFile(certificate());
    }

    /** The emulator's config contains container paths. Write a separate host-side config. */
    public void writeHostConfig() throws IOException {
        Path config = directory.resolve("host-config");
        Files.writeString(config, "[default]\nregion = " + region + "\nendpoint_url = " + endpoint()
            + "\nca_bundle = " + certificate() + "\ns3 =\n    signature_version = s3v4\n");
        if (Files.getFileStore(config).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
        }
    }

    public Map<String, Object> environment(Map<String, ?> inherited) {
        Map<String, Object> env = new LinkedHashMap<>(inherited);
        Map<String, Object> values = connectionEnvironment();
        // Windows environment names are case-insensitive. Replace aliases as well as canonical names.
        env.keySet().removeIf(key -> removesEnvironment(key) || values.containsKey(key.toUpperCase(Locale.ROOT)));
        env.putAll(values);
        return env;
    }

    static boolean removesEnvironment(String key) {
        String name = key.toUpperCase(Locale.ROOT);
        return CREDENTIAL_ENVIRONMENT.contains(name) || name.startsWith("AWS_CONTAINER_CREDENTIALS_")
            || name.startsWith("AWS_CONTAINER_AUTHORIZATION_") || name.startsWith("AWS_ENDPOINT_URL");
    }

    /** Values suitable for SDKs, the AWS CLI, and child Node.js processes; credentials stay in their file. */
    public Map<String, Object> connectionEnvironment() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("AWS_ENDPOINT_URL", endpoint());
        env.put("AWS_SHARED_CREDENTIALS_FILE", credentials().toString());
        env.put("AWS_CONFIG_FILE", directory.resolve("host-config").toString());
        env.put("AWS_CA_BUNDLE", certificate().toString());
        env.put("NODE_EXTRA_CA_CERTS", certificate().toString());
        env.put("AWS_SDK_LOAD_CONFIG", "1");
        env.put("AWS_EC2_METADATA_DISABLED", "true");
        env.put("AWS_DISABLE_HOST_PREFIX_INJECTION", "true");
        env.put("AWS_ENDPOINT_DISCOVERY_ENABLED", "false");
        env.put("CBOR_ENABLED", "false");
        env.put("AWS_REGION", region);
        env.put("AWS_DEFAULT_REGION", region);
        return env;
    }

    public Map<String, Object> systemProperties(Map<String, ?> inherited, Path consumerTrustStore) {
        Map<String, Object> props = new LinkedHashMap<>(inherited);
        props.keySet().removeIf(key -> key.startsWith("aws.endpointUrl") || Set.of("aws.accessKeyId",
            "aws.secretAccessKey", "aws.secretKey", "aws.sessionToken", "aws.profile", "aws.roleArn",
            "aws.roleSessionName", "aws.webIdentityTokenFile", "javax.net.ssl.trustStorePassword",
            "javax.net.ssl.trustStoreProvider").contains(key));
        props.put("aws.cborEnabled", "false");
        props.put("aws.endpointUrl", endpoint());
        props.put("aws.region", region);
        props.put("aws.profile", "default");
        props.put("aws.sharedCredentialsFile", credentials().toString());
        props.put("aws.configFile", directory.resolve("host-config").toString());
        props.put("aws.disableEc2Metadata", "true");
        props.put("aws.ignoreConfiguredEndpointUrls", "false");
        props.put("aws.endpointDiscoveryEnabled", "false");
        props.put("javax.net.ssl.trustStore", consumerTrustStore.toString());
        props.put("javax.net.ssl.trustStoreType", "PKCS12");
        // Gradle retains some SSL properties separately from getSystemProperties(); explicitly replace old values.
        props.put("javax.net.ssl.trustStorePassword", ConsumerTrustStore.PASSWORD);
        props.put("javax.net.ssl.trustStoreProvider", "");
        return props;
    }
}
