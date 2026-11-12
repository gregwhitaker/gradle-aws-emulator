package io.github.gregwhitaker.awsemulator.internal.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Runtime connection paths, never the generated credential values. */
public record EmulatorConnection(int port, String region, Path directory) {
    private static final Set<String> CREDENTIAL_ENVIRONMENT = Set.of(
        "AWS_ACCESS_KEY_ID", "AWS_ACCESS_KEY", "AWS_SECRET_ACCESS_KEY", "AWS_SECRET_KEY",
        "AWS_SESSION_TOKEN", "AWS_SECURITY_TOKEN", "AWS_PROFILE", "AWS_DEFAULT_PROFILE", "AWS_ROLE_ARN",
        "AWS_ROLE_SESSION_NAME", "AWS_WEB_IDENTITY_TOKEN_FILE", "AWS_CREDENTIAL_FILE",
        "AWS_IGNORE_CONFIGURED_ENDPOINT_URLS");

    public String endpoint() { return "https://127.0.0.1:" + port; }
    public Path trustStore() { return directory.resolve("truststore.p12"); }
    public Path credentials() { return directory.resolve("emulator_credentials"); }

    public boolean filesExist() {
        return Files.isRegularFile(credentials()) && Files.isRegularFile(trustStore())
            && Files.isRegularFile(directory.resolve("ca.pem"));
    }

    /** The emulator's config contains container paths. Write a separate host-side config. */
    public void writeHostConfig() throws IOException {
        Path config = directory.resolve("host-config");
        Files.writeString(config, "[default]\nregion = " + region + "\nendpoint_url = " + endpoint()
            + "\nca_bundle = " + directory.resolve("ca.pem") + "\ns3 =\n    signature_version = s3v4\n");
        if (Files.getFileStore(config).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------"));
        }
    }

    public Map<String, Object> environment(Map<String, ?> inherited) {
        Map<String, Object> env = new LinkedHashMap<>(inherited);
        env.keySet().removeIf(key -> CREDENTIAL_ENVIRONMENT.contains(key)
            || key.startsWith("AWS_CONTAINER_CREDENTIALS_") || key.startsWith("AWS_CONTAINER_AUTHORIZATION_")
            || key.startsWith("AWS_ENDPOINT_URL"));
        env.put("AWS_ENDPOINT_URL", endpoint());
        env.put("AWS_SHARED_CREDENTIALS_FILE", credentials().toString());
        env.put("AWS_CONFIG_FILE", directory.resolve("host-config").toString());
        env.put("AWS_CA_BUNDLE", directory.resolve("ca.pem").toString());
        env.put("AWS_EC2_METADATA_DISABLED", "true");
        env.put("AWS_DISABLE_HOST_PREFIX_INJECTION", "true");
        env.put("AWS_ENDPOINT_DISCOVERY_ENABLED", "false");
        env.put("CBOR_ENABLED", "false");
        env.put("AWS_REGION", region);
        env.put("AWS_DEFAULT_REGION", region);
        return env;
    }

    public Map<String, Object> systemProperties(Map<String, ?> inherited) {
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
        props.put("javax.net.ssl.trustStore", trustStore().toString());
        props.put("javax.net.ssl.trustStoreType", "PKCS12");
        return props;
    }
}
