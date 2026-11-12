package io.github.gregwhitaker.awsemulator.aws.sns;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class SnsTaskFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest
    @ValueSource(strings = {"groovy", "kotlin"})
    void configuresTypedTasksInBothDslsAndReusesConfigurationCacheWithoutDocker(String language) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'sns-fixture'\n");
        boolean kotlin = language.equals("kotlin");
        Files.writeString(project.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), kotlin ? """
            import io.github.gregwhitaker.awsemulator.aws.sns.*
            import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("this-command-does-not-exist") }
            val topic = tasks.register<CreateSnsTopicTask>("createTopic") { topicName.set("events") }
            val subscribed = tasks.register<CreateSnsTopicWithSqsEndpointTask>("createSubscribedTopic") {
                topicName.set("orders.fifo")
                topicAttributes.put("FifoTopic", "true")
                subscriptionAttributes.put("RawMessageDelivery", "true")
            }
            tasks.register<ListSnsTopicsTask>("customList")
            tasks.register<ListSnsTopicSubscriptionsTask>("customSubscriptions") { topicName.set("events") }
            tasks.register<DeleteSnsTopicTask>("customDelete") { topicName.set("events") }
            tasks.register<PublishSnsTask>("customPublish") { topicName.set("events"); message.set("hello") }
            check(topic.get().mode.get() == EmulatorMode.MANAGED)
            check(subscribed.get().queueName.get() == "orders-subscriber.fifo")
            listOf("listSnsTopics", "listSnsTopicSubscriptions", "deleteSnsTopic", "publishSns").forEach {
                check((tasks.named(it).get() as AbstractAwsResourceTask).mode.get() == EmulatorMode.DEVELOPMENT)
            }
            """ : """
            import io.github.gregwhitaker.awsemulator.aws.sns.*
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('this-command-does-not-exist') }
            def topic = tasks.register('createTopic', CreateSnsTopicTask) { topicName.set('events') }
            def subscribed = tasks.register('createSubscribedTopic', CreateSnsTopicWithSqsEndpointTask) {
                topicName.set('orders.fifo')
                topicAttributes.put('FifoTopic', 'true')
                subscriptionAttributes.put('RawMessageDelivery', 'true')
            }
            tasks.register('customList', ListSnsTopicsTask)
            tasks.register('customSubscriptions', ListSnsTopicSubscriptionsTask) { topicName.set('events') }
            tasks.register('customDelete', DeleteSnsTopicTask) { topicName.set('events') }
            tasks.register('customPublish', PublishSnsTask) { topicName.set('events'); message.set('hello') }
            assert topic.get().mode.get() == EmulatorMode.MANAGED
            assert subscribed.get().queueName.get() == 'orders-subscriber.fifo'
            ['listSnsTopics', 'listSnsTopicSubscriptions', 'deleteSnsTopic', 'publishSns'].each {
                assert tasks.named(it).get().mode.get() == EmulatorMode.DEVELOPMENT
            }
            """);
        String output = runner(project, "tasks", "--all").build().getOutput();
        assertTrue(output.contains("AWS Emulator - SNS tasks"), output);
        for (String task : new String[] {"createTopic", "createSubscribedTopic", "customList", "customSubscriptions", "customDelete", "customPublish",
            "listSnsTopics", "listSnsTopicSubscriptions", "deleteSnsTopic", "publishSns"}) assertTrue(output.contains(task + " - "), output);
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        String help = runner(project, "help", "--task", "publishSns").build().getOutput();
        for (String option : new String[] {"--topicName", "--message", "--subject", "--messageGroupId", "--messageDeduplicationId", "--mode"}) {
            assertTrue(help.contains(option), help);
        }
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test
    void validatesConfigurationAndCliInputsBeforeStartingDocker() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'sns-invalid-fixture'\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.sns.*
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('this-command-does-not-exist') }
            tasks.register('badTopic', CreateSnsTopicTask) { topicName.set('invalid/name') }
            tasks.register('badFifo', CreateSnsTopicTask) { topicName.set('events.fifo') }
            tasks.register('badQueue', CreateSnsTopicWithSqsEndpointTask) { topicName.set('events'); queueName.set('invalid/name') }
            tasks.register('badSubscription', CreateSnsTopicWithSqsEndpointTask) {
                topicName.set('events'); subscriptionAttributes.put('RawMessageDelivery', 'yes')
            }
            tasks.register('badTimeout', ListSnsTopicsTask) { apiCallTimeoutSeconds.set(0) }
            """);
        String output = runner(project, "badTopic", "badFifo", "badQueue", "badSubscription", "badTimeout", "--continue").buildAndFail().getOutput();
        for (String error : new String[] {"topicName must", "FIFO topics require", "queueName must", "RawMessageDelivery must", "apiCallTimeoutSeconds must be positive"}) {
            assertTrue(output.contains(error), output);
        }
        String cli = runner(project, "publishSns", "--topicName=events.fifo", "--message=hello").buildAndFail().getOutput();
        assertTrue(cli.contains("messageGroupId is required"), cli);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }
}
