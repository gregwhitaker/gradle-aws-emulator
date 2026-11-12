package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DynamoDbInitializerExecutorTest {
    @Test
    void passesTheExplicitClientToTheInitializerAndInvokesRunOnce() {
        var writes = new AtomicInteger();
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> {
            assertEquals("putItem", method.getName());
            var request = (PutItemRequest) args[0];
            assertEquals("catalog.products", request.tableName());
            assertEquals("seed", request.item().get("id").s());
            writes.incrementAndGet();
            return PutItemResponse.builder().build();
        });
        resolve(ValidInitializer.class).invoke(client);
        assertEquals(1, writes.get());
    }

    @Test
    void rejectsMissingClassesAndInvalidContractsBeforeInstantiation() {
        assertThrows(GradleException.class, () -> DynamoDbInitializerExecutor.resolve("missing.Initializer", getClass().getClassLoader()));
        assertThrows(GradleException.class, () -> resolve(WrongConstructor.class));
        assertThrows(GradleException.class, () -> resolve(WrongReturnType.class));
        assertThrows(GradleException.class, () -> resolve(StaticRun.class));
    }

    @Test
    void exposesTheOriginalInitializerFailure() {
        var failure = assertThrows(GradleException.class, () -> resolve(FailingInitializer.class).invoke(null));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("seed failure", failure.getCause().getMessage());
    }

    private DynamoDbInitializerExecutor resolve(Class<?> type) {
        return DynamoDbInitializerExecutor.resolve(type.getName(), type.getClassLoader());
    }

    public static class ValidInitializer {
        private final DynamoDbClient client;
        public ValidInitializer(DynamoDbClient client) { this.client = client; }
        public void run() {
            client.putItem(PutItemRequest.builder().tableName("catalog.products")
                .item(Map.of("id", AttributeValue.builder().s("seed").build())).build());
        }
    }
    public static class WrongConstructor {
        public WrongConstructor(String client) { throw new AssertionError("Must not instantiate"); }
        public void run() {}
    }
    public static class WrongReturnType {
        public WrongReturnType(DynamoDbClient client) { throw new AssertionError("Must not instantiate"); }
        public String run() { return "wrong"; }
    }
    public static class StaticRun {
        public StaticRun(DynamoDbClient client) { throw new AssertionError("Must not instantiate"); }
        public static void run() {}
    }
    public static class FailingInitializer {
        public FailingInitializer(DynamoDbClient client) {}
        public void run() { throw new IllegalStateException("seed failure"); }
    }
}
