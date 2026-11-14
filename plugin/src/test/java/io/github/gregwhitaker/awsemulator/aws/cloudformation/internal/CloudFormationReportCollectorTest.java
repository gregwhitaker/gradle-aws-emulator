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
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CloudFormationReportCollectorTest {
    @Test void followsStackAndResourcePagesExcludesDeletedStacksAndOmitsValues() {
        var client = SdkStub.client(CloudFormationClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listStacks" -> {
                String token = ((ListStacksRequest) args[0]).nextToken();
                assertTrue(token == null || token.equals("stacks"));
                yield ListStacksResponse.builder().stackSummaries(token == null
                    ? StackSummary.builder().stackId("deleted").stackStatus(StackStatus.DELETE_COMPLETE).build()
                    : StackSummary.builder().stackId("active").stackName("app").stackStatus(StackStatus.CREATE_COMPLETE).build())
                    .nextToken(token == null ? "stacks" : null).build();
            }
            case "describeStacks" -> DescribeStacksResponse.builder().stacks(Stack.builder()
                .parameters(Parameter.builder().parameterKey("Password").parameterValue("private-parameter").build())
                .outputs(Output.builder().outputKey("Token").outputValue("private-output").build()).build()).build();
            case "listStackResources" -> {
                var request = (ListStackResourcesRequest) args[0];
                assertEquals("active", request.stackName());
                assertTrue(request.nextToken() == null || request.nextToken().equals("resources"));
                yield ListStackResourcesResponse.builder().stackResourceSummaries(StackResourceSummary.builder()
                    .logicalResourceId(request.nextToken() == null ? "Bucket" : "Queue").physicalResourceId("physical")
                    .resourceType(request.nextToken() == null ? "AWS::S3::Bucket" : "AWS::SQS::Queue").build())
                    .nextToken(request.nextToken() == null ? "resources" : null).build();
            }
            default -> throw new AssertionError("Unexpected or mutating call: " + method.getName());
        });
        var section = new ReportSection("CloudFormation");
        new CloudFormationReportCollector().collect(client, section);
        assertEquals("COMPLETE", section.result().status());
        assertEquals(1, section.result().resources().size());
        var config = section.result().resources().get(0).configuration();
        assertEquals(List.of("Password"), config.get("parameterNames"));
        assertEquals(List.of("Token"), config.get("outputNames"));
        assertEquals(2, ((List<?>) config.get("resources")).size());
        assertFalse(config.toString().contains("private-"));
    }
}
