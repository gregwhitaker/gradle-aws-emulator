package io.github.gregwhitaker.awsemulator.aws.secretsmanager;

import io.github.gregwhitaker.awsemulator.aws.internal.ServiceSupport;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class SecretOperations {
    static final String NAME = "[a-zA-Z0-9/_+=.@-]{1,512}";
    record Secret(String name, String value, String keyId) {
        Secret {
            ServiceSupport.name(name, NAME, "secret name");
            if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 65536) throw new GradleException("Secret value must contain 1–65536 UTF-8 bytes.");
            if (keyId != null && keyId.isBlank()) throw new GradleException("keyId must not be blank.");
        }
        @Override public String toString() { return "Secret[" + name + ", value omitted]"; }
    }

    void put(SecretsManagerClient client, Secret secret, boolean overwrite) {
        try {
            DescribeSecretResponse existing;
            try { existing = client.describeSecret(DescribeSecretRequest.builder().secretId(secret.name()).build()); }
            catch (ResourceNotFoundException absent) {
                client.createSecret(CreateSecretRequest.builder().name(secret.name()).secretString(secret.value()).kmsKeyId(secret.keyId()).build());
                return;
            }
            if (existing.deletedDate() != null) throw new GradleException("Secret '" + secret.name() + "' is scheduled for deletion; restore or delete it explicitly before provisioning.");
            var value = client.getSecretValue(GetSecretValueRequest.builder().secretId(secret.name()).build()).secretString();
            if (Objects.equals(value, secret.value()) && (secret.keyId() == null || Objects.equals(secret.keyId(), existing.kmsKeyId()))) return;
            if (!overwrite) throw new GradleException("Secret '" + secret.name() + "' differs; set overwrite=true to replace it (values omitted).");
            var update = UpdateSecretRequest.builder().secretId(secret.name()).kmsKeyId(secret.keyId());
            if (!Objects.equals(value, secret.value())) update.secretString(secret.value());
            client.updateSecret(update.build());
        } catch (software.amazon.awssdk.core.exception.SdkException e) {
            String code = e instanceof SecretsManagerException service && service.awsErrorDetails() != null
                ? service.awsErrorDetails().errorCode() : e.getClass().getSimpleName();
            throw new GradleException("Writing secret '" + secret.name() + "' failed (" + code + "; values omitted).");
        }
    }

    List<SecretListEntry> list(SecretsManagerClient client) {
        List<SecretListEntry> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); String token = null;
        do {
            var page = client.listSecrets(ListSecretsRequest.builder().maxResults(100).includePlannedDeletion(true).nextToken(token).build());
            result.addAll(page.secretList()); token = ServiceSupport.next(page.nextToken(), seen);
        } while (token != null);
        return result;
    }

    void delete(SecretsManagerClient client, String name, boolean force, int days) {
        try {
            if (!force && client.describeSecret(DescribeSecretRequest.builder().secretId(name).build()).deletedDate() != null) return;
            var request = DeleteSecretRequest.builder().secretId(name);
            if (force) request.forceDeleteWithoutRecovery(true); else request.recoveryWindowInDays((long) days);
            client.deleteSecret(request.build());
        } catch (ResourceNotFoundException ignored) { }
    }
}
