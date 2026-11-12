package io.github.gregwhitaker.awsemulator.aws.internal;

import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorTls;
import org.gradle.api.GradleException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import software.amazon.awssdk.services.ssm.SsmClient;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import software.amazon.awssdk.services.eventbridge.EventBridgeClient;

import software.amazon.awssdk.services.kinesis.KinesisClient;

import software.amazon.awssdk.services.kms.KmsClient;

import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;

import java.net.URI;
import java.time.Duration;

/** Creates execution-scoped SDK clients with an explicit local connection and no credential fallback. */
public final class AwsClientFactory {
    private final EmulatorConnection connection;
    private final Duration timeout;

    public AwsClientFactory(EmulatorConnection connection, int timeoutSeconds) {
        this.connection = connection;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public S3Client s3() {
        return S3Client.builder()
            .endpointOverride(URI.create(connection.endpoint())).region(Region.of(connection.region()))
            .credentialsProvider(credentials()).httpClientBuilder(http())
            .forcePathStyle(true).crossRegionAccessEnabled(false)
            .fipsEnabled(false).dualstackEnabled(false)
            .overrideConfiguration(overrides()).build();
    }

    public SqsClient sqs() {
        return SqsClient.builder()
            .endpointOverride(URI.create(connection.endpoint())).region(Region.of(connection.region()))
            .credentialsProvider(credentials()).httpClientBuilder(http())
            .fipsEnabled(false).dualstackEnabled(false)
            .overrideConfiguration(overrides()).build();
    }

    public SnsClient sns() {
        return SnsClient.builder()
            .endpointOverride(URI.create(connection.endpoint())).region(Region.of(connection.region()))
            .credentialsProvider(credentials()).httpClientBuilder(http())
            .fipsEnabled(false).dualstackEnabled(false)
            .overrideConfiguration(overrides()).build();
    }

    public DynamoDbClient dynamoDb() {
        return DynamoDbClient.builder()
            .endpointOverride(URI.create(connection.endpoint())).region(Region.of(connection.region()))
            .credentialsProvider(credentials()).httpClientBuilder(http())
            .fipsEnabled(false).dualstackEnabled(false).endpointDiscoveryEnabled(false)
            .overrideConfiguration(overrides()).build();
    }

    public SsmClient ssm() {
        return SsmClient.builder()
            .endpointOverride(URI.create(connection.endpoint())).region(Region.of(connection.region()))
            .credentialsProvider(credentials()).httpClientBuilder(http())
            .fipsEnabled(false).dualstackEnabled(false)
            .overrideConfiguration(overrides()).build();
    }

    public SecretsManagerClient secretsManager() {
        return SecretsManagerClient.builder()
            .endpointOverride(URI.create(connection.endpoint())).region(Region.of(connection.region()))
            .credentialsProvider(credentials()).httpClientBuilder(http())
            .fipsEnabled(false).dualstackEnabled(false)
            .overrideConfiguration(overrides()).build();
    }

    public EventBridgeClient eventBridge() {
        return EventBridgeClient.builder()
            .endpointOverride(URI.create(connection.endpoint())).region(Region.of(connection.region()))
            .credentialsProvider(credentials()).httpClientBuilder(http())
            .fipsEnabled(false).dualstackEnabled(false)
            .overrideConfiguration(overrides()).build();
    }

    public KinesisClient kinesis() {
        return KinesisClient.builder()
            .endpointOverride(URI.create(connection.endpoint())).region(Region.of(connection.region()))
            .credentialsProvider(credentials()).httpClientBuilder(http())
            .fipsEnabled(false).dualstackEnabled(false)
            .overrideConfiguration(overrides()).build();
    }

    public KmsClient kms() {
        return KmsClient.builder()
            .endpointOverride(URI.create(connection.endpoint())).region(Region.of(connection.region()))
            .credentialsProvider(credentials()).httpClientBuilder(http())
            .fipsEnabled(false).dualstackEnabled(false)
            .overrideConfiguration(overrides()).build();
    }

    public CloudWatchLogsClient cloudWatchLogs() {
        return CloudWatchLogsClient.builder()
            .endpointOverride(URI.create(connection.endpoint())).region(Region.of(connection.region()))
            .credentialsProvider(credentials()).httpClientBuilder(http())
            .fipsEnabled(false).dualstackEnabled(false)
            .overrideConfiguration(overrides()).build();
    }

    private StaticCredentialsProvider credentials() {
        var profile = ProfileFile.builder().type(ProfileFile.Type.CREDENTIALS).content(connection.credentials())
            .build().profile("default").orElseThrow(() -> new GradleException("Emulator credentials have no default profile."));
        String access = profile.property("aws_access_key_id").filter(value -> !value.isBlank())
            .orElseThrow(() -> new GradleException("Emulator credentials have no access key."));
        String secret = profile.property("aws_secret_access_key").filter(value -> !value.isBlank())
            .orElseThrow(() -> new GradleException("Emulator credentials have no secret key."));
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret));
    }

    private UrlConnectionHttpClient.Builder http() {
        try {
            var trust = EmulatorTls.trustManagers(connection.trustStore());
            return UrlConnectionHttpClient.builder()
                .tlsTrustManagersProvider(() -> trust)
                .connectionTimeout(timeout).socketTimeout(timeout)
                .proxyConfiguration(proxy -> proxy.useSystemPropertyValues(false).useEnvironmentVariablesValues(false));
        } catch (Exception e) {
            throw new GradleException("Cannot load AWS Emulator TLS trust store.", e);
        }
    }

    private ClientOverrideConfiguration overrides() {
        return ClientOverrideConfiguration.builder().apiCallTimeout(timeout).apiCallAttemptTimeout(timeout)
            .putAdvancedOption(SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION, true).build();
    }
}
