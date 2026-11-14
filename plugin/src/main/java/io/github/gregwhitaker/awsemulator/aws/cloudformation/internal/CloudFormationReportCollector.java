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

package io.github.gregwhitaker.awsemulator.aws.cloudformation.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import static io.github.gregwhitaker.awsemulator.internal.report.ReportSection.*;

/** Reads active stacks and resource membership, omitting templates and parameter/output values. */
public final class CloudFormationReportCollector {
    public void collect(CloudFormationClient client, ReportSection section) {
        section.read("ListStacks", null, () -> pages(token -> {
            var page = client.listStacks(ListStacksRequest.builder().nextToken(token).build());
            for (var stack : page.stackSummaries()) {
                if ("DELETE_COMPLETE".equals(stack.stackStatusAsString())) continue;
                String id = stack.stackId() == null ? stack.stackName() : stack.stackId();
                var details = section.resource("AWS::CloudFormation::Stack", id);
                details.putAll(fields("name", stack.stackName(), "status", stack.stackStatusAsString(),
                    "createdAt", stack.creationTime() == null ? null : stack.creationTime().toString(),
                    "updatedAt", stack.lastUpdatedTime() == null ? null : stack.lastUpdatedTime().toString()));
                section.read("DescribeStacks", id, () -> {
                    var stacks = client.describeStacks(DescribeStacksRequest.builder().stackName(id).build()).stacks();
                    if (stacks.size() != 1) throw new IllegalStateException("Stack disappeared during collection.");
                    var description = stacks.get(0);
                    details.put("parameterNames", description.parameters().stream().map(Parameter::parameterKey).sorted().toList());
                    details.put("outputNames", description.outputs().stream().map(Output::outputKey).sorted().toList());
                });
                var resources = new ArrayList<Map<String, Object>>();
                details.put("resources", resources);
                section.read("ListStackResources", id, () -> pages(next -> {
                    var response = client.listStackResources(ListStackResourcesRequest.builder().stackName(id).nextToken(next).build());
                    response.stackResourceSummaries().forEach(resource -> resources.add(fields(
                        "logicalId", resource.logicalResourceId(), "physicalId", resource.physicalResourceId(),
                        "type", resource.resourceType(), "status", resource.resourceStatusAsString())));
                    return response.nextToken();
                }));
                resources.sort(Comparator.comparing(Object::toString));
            }
            return page.nextToken();
        }));
    }
}
