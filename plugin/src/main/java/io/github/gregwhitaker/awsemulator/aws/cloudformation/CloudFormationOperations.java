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
import io.github.gregwhitaker.awsemulator.aws.internal.ResourceFiles;
import io.github.gregwhitaker.awsemulator.aws.internal.ServiceSupport;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/** Stack lifecycle operations; terminal status, not request acceptance, determines success. */
final class CloudFormationOperations {
    private static final Set<String> UPDATABLE = Set.of("CREATE_COMPLETE", "UPDATE_COMPLETE", "UPDATE_ROLLBACK_COMPLETE");

    Stack deploy(CloudFormationClient client, String name, String template, List<Parameter> parameters,
            List<Tag> tags, List<String> capabilities, int timeout) {
        ServiceSupport.positive(timeout);
        Stack existing = describe(client, name);
        String id;
        String expected;
        if (existing == null) {
            var response = client.createStack(CreateStackRequest.builder().stackName(name).templateBody(template)
                .parameters(parameters).tags(tags).capabilitiesWithStrings(capabilities).build());
            id = response.stackId();
            expected = "CREATE_COMPLETE";
        } else {
            if (!UPDATABLE.contains(existing.stackStatusAsString())) throw failure(name, existing);
            try {
                var response = client.updateStack(UpdateStackRequest.builder().stackName(name).templateBody(template)
                    .parameters(parameters).tags(tags).capabilitiesWithStrings(capabilities).build());
                id = response.stackId();
            } catch (CloudFormationException error) {
                if (validationError(error) && error.getMessage() != null
                        && error.getMessage().contains("No updates are to be performed")) return existing;
                throw error;
            }
            expected = "UPDATE_COMPLETE";
        }
        String target = id == null || id.isBlank() ? name : id;
        var result = new AtomicReference<Stack>();
        ServiceSupport.await("waiting for CloudFormation stack " + name + " to reach " + expected, timeout, () -> {
            Stack stack = describe(client, target);
            if (stack == null) throw new GradleException("CloudFormation stack " + name + " disappeared during deployment.");
            String status = stack.stackStatusAsString();
            if (expected.equals(status)) { result.set(stack); return true; }
            if (status == null || status.contains("FAILED") || status.contains("ROLLBACK") || status.startsWith("DELETE")) {
                throw failure(name, stack);
            }
            return false;
        });
        return result.get();
    }

    void delete(CloudFormationClient client, String name, int timeout) {
        ServiceSupport.positive(timeout);
        Stack existing = describe(client, name);
        if (existing == null || "DELETE_COMPLETE".equals(existing.stackStatusAsString())) return;
        String status = existing.stackStatusAsString();
        if (!"DELETE_IN_PROGRESS".equals(status)) {
            if (status == null || status.endsWith("_IN_PROGRESS")) throw failure(name, existing);
            client.deleteStack(DeleteStackRequest.builder().stackName(name).build());
        }
        String target = existing.stackId() == null ? name : existing.stackId();
        ServiceSupport.await("deleting CloudFormation stack " + name, timeout, () -> {
            Stack stack = describe(client, target);
            if (stack == null || "DELETE_COMPLETE".equals(stack.stackStatusAsString())) return true;
            if ("DELETE_FAILED".equals(stack.stackStatusAsString())) throw failure(name, stack);
            return false;
        });
    }

    List<StackSummary> list(CloudFormationClient client) {
        var result = new ArrayList<StackSummary>();
        var seen = new HashSet<String>();
        String token = null;
        do {
            var page = client.listStacks(ListStacksRequest.builder().nextToken(token).build());
            page.stackSummaries().stream().filter(stack -> !"DELETE_COMPLETE".equals(stack.stackStatusAsString())).forEach(result::add);
            token = ServiceSupport.next(page.nextToken(), seen);
        } while (token != null);
        result.sort(Comparator.comparing(StackSummary::stackName));
        return List.copyOf(result);
    }

    private Stack describe(CloudFormationClient client, String name) {
        try {
            var stacks = client.describeStacks(DescribeStacksRequest.builder().stackName(name).build()).stacks();
            if (stacks.size() > 1) throw new GradleException("CloudFormation returned multiple stacks for " + name + ".");
            return stacks.isEmpty() ? null : stacks.get(0);
        } catch (CloudFormationException error) {
            if (validationError(error) && error.getMessage() != null && error.getMessage().contains("does not exist")) return null;
            throw error;
        }
    }

    private static boolean validationError(CloudFormationException error) {
        return error.statusCode() == 400 && error.awsErrorDetails() != null
            && "ValidationError".equals(error.awsErrorDetails().errorCode());
    }

    private static GradleException failure(String name, Stack stack) {
        String reason = stack.stackStatusReason();
        return new GradleException("CloudFormation stack " + name + " is " + stack.stackStatusAsString()
            + (reason == null || reason.isBlank() ? "." : ": " + reason)
            + " Inspect the stack before retrying; a rolled-back or failed deployment is not success.");
    }

    static List<Parameter> parameters(Map<String, String> values) {
        var result = new ArrayList<Parameter>();
        new TreeMap<>(values).forEach((key, value) -> {
            ServiceSupport.name(key, "[A-Za-z][A-Za-z0-9]*", "CloudFormation parameter name");
            result.add(Parameter.builder().parameterKey(key).parameterValue(value).build());
        });
        return List.copyOf(result);
    }

    static List<Tag> tags(Map<String, String> values) {
        if (values.size() > 50) throw new GradleException("CloudFormation tags must contain at most 50 entries.");
        var result = new ArrayList<Tag>();
        new TreeMap<>(values).forEach((key, value) -> {
            if (key.isBlank() || key.length() > 128 || key.startsWith("aws:") || value.length() > 256) {
                throw new GradleException("Invalid CloudFormation tag key or value (values omitted).");
            }
            result.add(Tag.builder().key(key).value(value).build());
        });
        return List.copyOf(result);
    }

    static List<String> capabilities(List<String> values) {
        if (!Set.of("CAPABILITY_IAM", "CAPABILITY_NAMED_IAM").containsAll(values)) {
            throw new GradleException("CloudFormation capabilities may contain only CAPABILITY_IAM and CAPABILITY_NAMED_IAM; transforms are not supported.");
        }
        return values.stream().distinct().sorted().toList();
    }

    static void writeOutputs(Stack stack, Path path) {
        var values = new TreeMap<String, String>();
        for (Output output : stack.outputs()) values.put(output.outputKey(), output.outputValue());
        try { ResourceFiles.write(path, new ObjectMapper().writeValueAsBytes(values)); }
        catch (java.io.IOException error) { throw new GradleException("Cannot serialize CloudFormation outputs (values omitted)."); }
    }
}
