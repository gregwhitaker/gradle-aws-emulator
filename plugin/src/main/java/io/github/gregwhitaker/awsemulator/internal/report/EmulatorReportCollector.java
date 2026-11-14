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

package io.github.gregwhitaker.awsemulator.internal.report;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.aws.s3.internal.S3ReportCollector;
import io.github.gregwhitaker.awsemulator.aws.sqs.internal.SqsReportCollector;
import io.github.gregwhitaker.awsemulator.aws.sns.internal.SnsReportCollector;
import io.github.gregwhitaker.awsemulator.aws.dynamodb.internal.DynamoDbReportCollector;
import io.github.gregwhitaker.awsemulator.aws.cloudformation.internal.CloudFormationReportCollector;
import java.util.List;
import java.util.function.Consumer;

/** Opens and closes one execution-scoped client per service, isolating failures between sections. */
public final class EmulatorReportCollector {
    public List<ReportSection.Result> collect(AwsClientFactory clients) {
        return List.of(
            collect("S3", section -> { try (var client = clients.s3()) { new S3ReportCollector().collect(client, section); } }),
            collect("SQS", section -> { try (var client = clients.sqs()) { new SqsReportCollector().collect(client, section); } }),
            collect("SNS", section -> { try (var client = clients.sns()) { new SnsReportCollector().collect(client, section); } }),
            collect("DynamoDB", section -> { try (var client = clients.dynamoDb()) { new DynamoDbReportCollector().collect(client, section); } }),
            collect("CloudFormation", section -> { try (var client = clients.cloudFormation()) { new CloudFormationReportCollector().collect(client, section); } })
        );
    }

    private ReportSection.Result collect(String name, Consumer<ReportSection> collector) {
        var section = new ReportSection(name);
        section.read("Connect", null, () -> collector.accept(section));
        return section.result();
    }
}
