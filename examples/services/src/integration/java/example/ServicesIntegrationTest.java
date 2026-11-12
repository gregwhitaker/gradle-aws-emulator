package example;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ServicesIntegrationTest {
    @Test void consumesProvisionedServices() throws Exception {
        var config = ClientOverrideConfiguration.builder().apiCallTimeout(Duration.ofSeconds(15))
            .putAdvancedOption(SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION, true).build();
        try (var ssm = SsmClient.builder().overrideConfiguration(config).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
             var secrets = SecretsManagerClient.builder().overrideConfiguration(config).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
             var events = EventBridgeClient.builder().overrideConfiguration(config).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
             var streams = KinesisClient.builder().overrideConfiguration(config).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
             var sqs = SqsClient.builder().overrideConfiguration(config).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
             var logs = CloudWatchLogsClient.builder().overrideConfiguration(config).httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            assertEquals("http://localhost:8080", ssm.getParameter(r -> r.name("/example/service/url")).parameter().value());
            assertEquals("catalog,checkout", ssm.getParameter(r -> r.name("/example/service/features")).parameter().value());
            assertEquals("local-token", ssm.getParameter(r -> r.name("/example/service/token").withDecryption(true)).parameter().value());
            assertTrue(secrets.getSecretValue(r -> r.secretId("example/database")).secretString().contains("local-password"));
            assertEquals(1, events.listTargetsByRule(r -> r.eventBusName("example-services").rule("orders")).targets().size());
            String url = sqs.getQueueUrl(r -> r.queueName("example-service-events")).queueUrl();
            var messages = sqs.receiveMessage(r -> r.queueUrl(url).maxNumberOfMessages(10).waitTimeSeconds(5)).messages();
            assertEquals(1, messages.size());
            assertTrue(messages.get(0).body().contains("order-1"));
            assertFalse(messages.get(0).body().contains("ignored"));
            var shard = streams.listShards(r -> r.streamName("example-service-records")).shards().get(0).shardId();
            String iterator = streams.getShardIterator(r -> r.streamName("example-service-records").shardId(shard)
                .shardIteratorType(ShardIteratorType.TRIM_HORIZON)).shardIterator();
            List<String> records = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (records.size() < 2 && System.nanoTime() < deadline) {
                String cursor = iterator;
                var result = streams.getRecords(r -> r.shardIterator(cursor));
                result.records().forEach(record -> records.add(record.data().asUtf8String()));
                iterator = result.nextShardIterator();
                if (records.size() < 2) Thread.sleep(200);
            }
            assertEquals(List.of("first-record", "second-record"), records.stream().sorted().toList());
            assertEquals("local encryption fixture\n", Files.readString(Path.of(System.getProperty("example.decrypted"))));
            logs.putLogEvents(r -> r.logGroupName("/example/services").logStreamName("application")
                .logEvents(InputLogEvent.builder().timestamp(System.currentTimeMillis()).message("application ready").build()));
            assertFalse(Boolean.getBoolean("example.fail"), "Requested example failure");
        }
    }
}
