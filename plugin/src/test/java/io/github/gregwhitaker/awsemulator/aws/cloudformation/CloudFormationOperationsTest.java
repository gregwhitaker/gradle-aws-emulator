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

package io.github.gregwhitaker.awsemulator.aws.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.gregwhitaker.awsemulator.testsupport.SdkStub.client;
import static org.junit.jupiter.api.Assertions.*;

class CloudFormationOperationsTest {
    @TempDir Path directory;
    private final CloudFormationOperations operations = new CloudFormationOperations();

    @Test void creationWaitsForSuccessAndPassesParametersTagsAndCapabilities() throws Exception {
        var reads = new AtomicInteger();
        var sdk = client(CloudFormationClient.class, (p, method, args) -> switch (method.getName()) {
            case "describeStacks" -> {
                int read = reads.getAndIncrement();
                if (read == 0) throw missing();
                assertEquals("stack-id", ((DescribeStacksRequest) args[0]).stackName());
                yield response(read == 1 ? "CREATE_IN_PROGRESS" : "CREATE_COMPLETE");
            }
            case "createStack" -> {
                var request = (CreateStackRequest) args[0];
                assertEquals("test", request.stackName());
                assertEquals("template", request.templateBody());
                assertEquals("orders", request.parameters().get(0).parameterValue());
                assertEquals("test", request.tags().get(0).value());
                assertEquals(List.of("CAPABILITY_IAM"), request.capabilitiesAsStrings());
                yield CreateStackResponse.builder().stackId("stack-id").build();
            }
            default -> throw new AssertionError(method.getName());
        });
        Stack stack = operations.deploy(sdk, "test", "template", CloudFormationOperations.parameters(Map.of("QueueName", "orders")),
            CloudFormationOperations.tags(Map.of("environment", "test")), List.of("CAPABILITY_IAM"), 2);
        assertEquals(3, reads.get());
        CloudFormationOperations.writeOutputs(stack, directory.resolve("outputs.json"));
        assertEquals("local-value", new ObjectMapper().readTree(directory.resolve("outputs.json").toFile()).path("Name").asText());
    }

    @Test void updatesWaitForUpdateCompleteAndNoChangesAreReused() {
        var reads = new AtomicInteger();
        var sdk = client(CloudFormationClient.class, (p, method, args) -> switch (method.getName()) {
            case "describeStacks" -> response(reads.getAndIncrement() == 0 ? "CREATE_COMPLETE" : "UPDATE_COMPLETE");
            case "updateStack" -> UpdateStackResponse.builder().stackId("stack-id").build();
            default -> throw new AssertionError(method.getName());
        });
        assertEquals("UPDATE_COMPLETE", deploy(sdk).stackStatusAsString());
        var unchanged = client(CloudFormationClient.class, (p, method, args) -> switch (method.getName()) {
            case "describeStacks" -> response("UPDATE_COMPLETE");
            case "updateStack" -> throw error("ValidationError", "No updates are to be performed.");
            default -> throw new AssertionError(method.getName());
        });
        assertEquals("UPDATE_COMPLETE", deploy(unchanged).stackStatusAsString());
    }

    @Test void failedStackAndRollbackAreNeverReportedAsSuccess() {
        for (String state : List.of("CREATE_FAILED", "ROLLBACK_IN_PROGRESS", "ROLLBACK_COMPLETE", "UPDATE_ROLLBACK_COMPLETE")) {
            var reads = new AtomicInteger();
            var sdk = client(CloudFormationClient.class, (p, method, args) -> switch (method.getName()) {
                case "describeStacks" -> {
                    if (reads.getAndIncrement() == 0) throw missing();
                    yield response(state);
                }
                case "createStack" -> CreateStackResponse.builder().stackId("stack-id").build();
                default -> throw new AssertionError(method.getName());
            });
            assertTrue(assertThrows(GradleException.class, () -> deploy(sdk)).getMessage().contains(state));
        }
    }

    @Test void busyStacksAreNotUpdatedAndNonNotFoundErrorsPropagate() {
        var busy = client(CloudFormationClient.class, (p, method, args) -> {
            assertEquals("describeStacks", method.getName());
            return response("UPDATE_IN_PROGRESS");
        });
        assertThrows(GradleException.class, () -> deploy(busy));
        var denied = client(CloudFormationClient.class, (p, method, args) -> { throw error("AccessDenied", "does not exist"); });
        assertThrows(CloudFormationException.class, () -> deploy(denied));
        assertThrows(CloudFormationException.class, () -> operations.delete(denied, "test", 1));
        var badUpdate = client(CloudFormationClient.class, (p, method, args) -> {
            if (method.getName().equals("describeStacks")) return response("CREATE_COMPLETE");
            throw error("ValidationError", "Invalid template");
        });
        assertThrows(CloudFormationException.class, () -> deploy(badUpdate));
    }

    @Test void stuckOperationsTimeOut() {
        var reads = new AtomicInteger();
        var sdk = client(CloudFormationClient.class, (p, method, args) -> switch (method.getName()) {
            case "describeStacks" -> {
                if (reads.getAndIncrement() == 0) throw missing();
                yield response("CREATE_IN_PROGRESS");
            }
            case "createStack" -> CreateStackResponse.builder().build();
            default -> throw new AssertionError(method.getName());
        });
        assertTrue(assertThrows(GradleException.class, () -> operations.deploy(sdk, "test", "body", List.of(), List.of(), List.of(), 1))
            .getMessage().contains("Timed out"));
    }

    @Test void deletionWaitsAndHandlesAbsenceButNotFailures() {
        var reads = new AtomicInteger();
        var sdk = client(CloudFormationClient.class, (p, method, args) -> switch (method.getName()) {
            case "describeStacks" -> {
                int read = reads.getAndIncrement();
                if (read > 1) throw missing();
                yield response(read == 0 ? "CREATE_COMPLETE" : "DELETE_IN_PROGRESS");
            }
            case "deleteStack" -> DeleteStackResponse.builder().build();
            default -> throw new AssertionError(method.getName());
        });
        operations.delete(sdk, "test", 2);
        operations.delete(sdk, "test", 2);
        var failed = client(CloudFormationClient.class, (p, method, args) ->
            method.getName().equals("deleteStack") ? DeleteStackResponse.builder().build() : response("DELETE_FAILED"));
        assertTrue(assertThrows(GradleException.class, () -> operations.delete(failed, "test", 1)).getMessage().contains("DELETE_FAILED"));
    }

    @Test void listsAllPagesExcludesDeletedStacksAndRejectsRepeatedTokens() {
        var tokens = new ArrayList<String>();
        var sdk = client(CloudFormationClient.class, (p, method, args) -> {
            String token = ((ListStacksRequest) args[0]).nextToken();
            tokens.add(token);
            return ListStacksResponse.builder().stackSummaries(
                StackSummary.builder().stackName(token == null ? "z" : "a").stackStatus("CREATE_COMPLETE").build(),
                StackSummary.builder().stackName("deleted").stackStatus("DELETE_COMPLETE").build())
                .nextToken(token == null ? "next" : null).build();
        });
        assertEquals(List.of("a", "z"), operations.list(sdk).stream().map(StackSummary::stackName).toList());
        assertEquals(2, tokens.size());
        var repeated = client(CloudFormationClient.class, (p, method, args) -> ListStacksResponse.builder().nextToken("same").build());
        assertThrows(GradleException.class, () -> operations.list(repeated));
    }

    private Stack deploy(CloudFormationClient sdk) {
        return operations.deploy(sdk, "test", "template", List.of(), List.of(), List.of(), 2);
    }

    private static DescribeStacksResponse response(String status) {
        return DescribeStacksResponse.builder().stacks(Stack.builder().stackName("test").stackId("stack-id").stackStatus(status)
            .outputs(Output.builder().outputKey("Name").outputValue("local-value").build()).build()).build();
    }

    private static CloudFormationException missing() { return error("ValidationError", "Stack with id test does not exist"); }
    private static CloudFormationException error(String code, String message) {
        var builder = CloudFormationException.builder();
        builder.statusCode(400);
        builder.message(message);
        builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(message).build());
        return (CloudFormationException) builder.build();
    }
}
