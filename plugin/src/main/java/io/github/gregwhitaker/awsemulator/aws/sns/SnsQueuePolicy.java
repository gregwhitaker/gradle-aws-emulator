package io.github.gregwhitaker.awsemulator.aws.sns;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gradle.api.GradleException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Adds one narrowly scoped SNS delivery grant while retaining every unrelated policy field. */
final class SnsQueuePolicy {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static String grant(String existing, String topicArn, String queueArn) {
        try {
            ObjectNode policy;
            if (existing == null || existing.isBlank()) {
                policy = JSON.createObjectNode().put("Version", "2012-10-17");
            } else {
                var parsed = JSON.readTree(existing);
                if (!(parsed instanceof ObjectNode object)) throw new IllegalArgumentException();
                policy = object;
            }
            ArrayNode statements;
            var current = policy.get("Statement");
            if (current == null) statements = policy.putArray("Statement");
            else if (current.isArray()) statements = (ArrayNode) current;
            else if (current.isObject()) {
                statements = JSON.createArrayNode().add(current);
                policy.set("Statement", statements);
            } else throw new IllegalArgumentException();

            String sid = "AwsEmulatorSns" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(topicArn.getBytes(StandardCharsets.UTF_8)));
            var grant = JSON.createObjectNode().put("Sid", sid).put("Effect", "Allow");
            grant.putObject("Principal").put("Service", "sns.amazonaws.com");
            grant.put("Action", "sqs:SendMessage").put("Resource", queueArn);
            grant.putObject("Condition").putObject("ArnEquals").put("aws:SourceArn", topicArn);
            boolean present = false;
            for (var statement : statements) {
                if (!statement.isObject()) throw new IllegalArgumentException();
                if (grant.equals(statement)) present = true;
                else if (sid.equals(statement.path("Sid").asText())) {
                    throw new GradleException("The SQS policy contains a conflicting SNS delivery statement; existing permissions were not changed.");
                }
            }
            if (present) return existing;
            statements.add(grant);
            return policy.toString();
        } catch (GradleException error) { throw error; }
        catch (Exception error) {
            throw new GradleException("Cannot merge the existing SQS queue policy; expected a JSON policy with object statements. Existing permissions were not changed.");
        }
    }
}
