package io.github.gregwhitaker.awsemulator.aws.sns;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.internal.docker.ProcessCommandRunner;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.sqs.SqsClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class SnsTasksIntegrationTest {
    @TempDir Path project;

    @Test
    void provisionsReusesPublishesAndDeletesTopicsWithRealSqsDelivery() throws Exception {
        fixture();
        try {
            runner(project, "provision").build();
            var clients = new AwsClientFactory(developmentConnection(), 30);
            try (var sns = clients.sns(); var sqs = clients.sqs()) {
                String arn = sns.listTopics().topics().stream().map(topic -> topic.topicArn())
                    .filter(topic -> topic.endsWith(":events")).findFirst().orElseThrow();
                String queueUrl = queueUrl(sqs, "events-subscriber");
                String subscription = sns.listSubscriptionsByTopic(r -> r.topicArn(arn)).subscriptions().get(0).subscriptionArn();
                sqs.sendMessage(r -> r.queueUrl(queueUrl).messageBody("retained"));
                var json = new ObjectMapper();
                var policy = (ObjectNode) json.readTree(sqs.getQueueAttributes(r -> r.queueUrl(queueUrl).attributeNamesWithStrings("Policy"))
                    .attributesAsStrings().get("Policy"));
                policy.withArray("Statement").addObject().put("Sid", "Preserved").put("Effect", "Deny")
                    .put("Principal", "*").put("Action", "sqs:DeleteQueue").put("Resource", "*");
                sqs.setQueueAttributes(r -> r.queueUrl(queueUrl).attributesWithStrings(Map.of("Policy", policy.toString())));
                sns.setSubscriptionAttributes(r -> r.subscriptionArn(subscription).attributeName("RawMessageDelivery").attributeValue("false"));

                String repeated = runner(project, "provision").build().getOutput();
                assertTrue(repeated.contains("Reusing configuration cache"), repeated);
                var subscriptions = sns.listSubscriptionsByTopic(r -> r.topicArn(arn)).subscriptions();
                assertEquals(1, subscriptions.size());
                assertEquals(subscription, subscriptions.get(0).subscriptionArn());
                assertEquals("true", sns.getSubscriptionAttributes(r -> r.subscriptionArn(subscription)).attributes().get("RawMessageDelivery"));
                assertEquals(policy, json.readTree(sqs.getQueueAttributes(r -> r.queueUrl(queueUrl).attributeNamesWithStrings("Policy"))
                    .attributesAsStrings().get("Policy")));
                assertEquals("retained", receive(sqs, queueUrl).body());

                String listed = runner(project, "listSnsTopics", "listSnsTopicSubscriptions", "--topicName=events").build().getOutput();
                assertTrue(listed.contains("events\t" + arn), listed);
                assertTrue(listed.contains(subscription), listed);
                assertTrue(listed.contains("orders.fifo\t"), listed);
                String published = runner(project, "publishSns", "--topicName=events", "--message=delivered via SNS").build().getOutput();
                assertTrue(published.contains("Published SNS message to events"), published);
                assertEquals("delivered via SNS", receive(sqs, queueUrl).body());

                for (int i = 0; i < 2; i++) runner(project, "publishSns", "--topicName=orders.fifo", "--message=identical", "--messageGroupId=fixture").build();
                String fifoUrl = queueUrl(sqs, "orders-subscriber.fifo");
                for (int i = 0; i < 2; i++) {
                    var message = receive(sqs, fifoUrl);
                    assertEquals("identical", message.body());
                    sqs.deleteMessage(r -> r.queueUrl(fifoUrl).receiptHandle(message.receiptHandle()));
                }

                String conflict = runner(project, "conflictTopic").buildAndFail().getOutput();
                assertTrue(conflict.contains("different DisplayName"), conflict);
                for (int i = 0; i < 2; i++) runner(project, "deleteSnsTopic", "--topicName=events").build();
                assertFalse(sns.listTopics().topics().stream().anyMatch(topic -> topic.topicArn().equals(arn)));
                assertEquals(queueUrl, queueUrl(sqs, "events-subscriber"));
                String missing = runner(project, "publishSns", "--topicName=events", "--message=missing").buildAndFail().getOutput();
                assertTrue(missing.contains("does not exist"), missing);
                assertFalse(sns.listTopics().topics().stream().anyMatch(topic -> topic.topicArn().equals(arn)));
            }
        } finally {
            runner(project, "stopAwsEmulator").build();
        }
        assertEquals("", containers());
    }

    @Test
    void managedTasksShareAnInstanceRunAgainOnCacheReuseAndCleanUp() throws Exception {
        fixture();
        for (int run = 0; run < 2; run++) {
            String output = runner(project, "listFixtureSubscriptions", "listFixtureQueues", "-Pmanaged=true").build().getOutput();
            assertTrue(output.contains("Published SNS message to events"), output);
            assertTrue(output.contains("\tsqs\tarn:aws:sqs:"), output);
            assertTrue(output.lines().anyMatch(line -> line.startsWith("events-subscriber\t") && line.endsWith("\t1")), output);
            if (run == 1) assertTrue(output.contains("Reusing configuration cache"), output);
            assertEquals("", containers());
            try (var paths = Files.list(project.resolve(".aws-emulator"))) {
                assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith("managed-")));
            }
        }
    }

    private software.amazon.awssdk.services.sqs.model.Message receive(SqsClient sqs, String url) {
        var messages = sqs.receiveMessage(r -> r.queueUrl(url).waitTimeSeconds(10).maxNumberOfMessages(1)).messages();
        assertEquals(1, messages.size(), "Expected a message at " + url);
        return messages.get(0);
    }

    private String queueUrl(SqsClient sqs, String name) { return sqs.getQueueUrl(r -> r.queueName(name)).queueUrl(); }

    private EmulatorConnection developmentConnection() throws Exception {
        try (var directories = Files.list(project.resolve(".aws-emulator/development/.aws/emulator"))) {
            Path directory = directories.filter(Files::isDirectory).findFirst().orElseThrow();
            return new EmulatorConnection(Integer.parseInt(directory.getFileName().toString()), "us-east-1", directory);
        }
    }

    private String containers() throws Exception {
        return new ProcessCommandRunner().run(List.of("docker", "container", "ls", "--all", "--filter",
            "label=io.github.gregwhitaker.aws-emulator.owner=" + EmulatorSettings.hash(project.toRealPath().toString()),
            "--format", "{{.ID}}"), Duration.ofSeconds(30)).requireSuccess("Checking SNS task cleanup");
    }

    private void fixture() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'sns-integration-fixture'\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.sns.*
            import io.github.gregwhitaker.awsemulator.aws.sqs.ListSqsQueuesTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { port.set(0) }
            def runtimeMode = providers.gradleProperty('managed').getOrElse('false').toBoolean() ? EmulatorMode.MANAGED : EmulatorMode.DEVELOPMENT
            def standard = tasks.register('createSubscribedTopic', CreateSnsTopicWithSqsEndpointTask) {
                mode.set(runtimeMode)
                topicName.set('events')
                subscriptionAttributes.put('RawMessageDelivery', 'true')
            }
            def fifo = tasks.register('createFifoTopic', CreateSnsTopicWithSqsEndpointTask) {
                mode.set(runtimeMode)
                topicName.set('orders.fifo')
                topicAttributes.put('FifoTopic', 'true')
                queueAttributes.put('ContentBasedDeduplication', 'true')
                subscriptionAttributes.put('RawMessageDelivery', 'true')
            }
            def standalone = tasks.register('createTopic', CreateSnsTopicTask) {
                mode.set(runtimeMode)
                topicName.set('standalone')
                attributes.put('DisplayName', 'Original')
            }
            tasks.register('conflictTopic', CreateSnsTopicTask) {
                mode.set(runtimeMode)
                topicName.set('standalone')
                attributes.put('DisplayName', 'Changed')
            }
            def provision = tasks.register('provision') { dependsOn(standard, fifo, standalone) }
            def publish = tasks.register('publishFixture', PublishSnsTask) {
                dependsOn(provision)
                mode.set(runtimeMode)
                topicName.set('events')
                message.set('managed delivery')
            }
            tasks.register('listFixtureSubscriptions', ListSnsTopicSubscriptionsTask) {
                dependsOn(publish)
                mode.set(runtimeMode)
                topicName.set('events')
            }
            tasks.register('listFixtureQueues', ListSqsQueuesTask) {
                dependsOn(publish)
                mode.set(runtimeMode)
            }
            """);
    }
}
