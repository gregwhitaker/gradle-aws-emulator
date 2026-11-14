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

package io.github.gregwhitaker.awsemulator.aws.dynamodb.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import static io.github.gregwhitaker.awsemulator.internal.report.ReportSection.*;

/** Describes table schemas, indexes, and service-reported counts without scanning items. */
public final class DynamoDbReportCollector {
    public void collect(DynamoDbClient client, ReportSection section) {
        section.read("ListTables", null, () -> pages(token -> {
            var page = client.listTables(ListTablesRequest.builder().exclusiveStartTableName(token).build());
            for (String name : page.tableNames()) {
                var details = section.resource("AWS::DynamoDB::Table", name);
                section.read("DescribeTable", name, () -> {
                    var table = client.describeTable(DescribeTableRequest.builder().tableName(name).build()).table();
                    details.putAll(fields("arn", table.tableArn(), "status", table.tableStatusAsString(),
                        "createdAt", table.creationDateTime() == null ? null : table.creationDateTime().toString(),
                        "itemCount", table.itemCount(), "sizeBytes", table.tableSizeBytes(),
                        "billingMode", table.billingModeSummary() == null ? "PROVISIONED" : table.billingModeSummary().billingModeAsString(),
                        "keySchema", keys(table.keySchema()), "attributes", table.attributeDefinitions().stream()
                            .sorted(Comparator.comparing(AttributeDefinition::attributeName))
                            .map(a -> fields("name", a.attributeName(), "type", a.attributeTypeAsString())).toList()));
                    if (table.provisionedThroughput() != null) details.put("throughput", fields(
                        "readCapacityUnits", table.provisionedThroughput().readCapacityUnits(),
                        "writeCapacityUnits", table.provisionedThroughput().writeCapacityUnits()));
                    details.put("globalSecondaryIndexes", table.globalSecondaryIndexes().stream()
                        .sorted(Comparator.comparing(GlobalSecondaryIndexDescription::indexName))
                        .map(index -> fields("name", index.indexName(), "arn", index.indexArn(), "status", index.indexStatusAsString(),
                            "keySchema", keys(index.keySchema()), "projection", projection(index.projection()),
                            "itemCount", index.itemCount(), "sizeBytes", index.indexSizeBytes())).toList());
                    details.put("localSecondaryIndexes", table.localSecondaryIndexes().stream()
                        .sorted(Comparator.comparing(LocalSecondaryIndexDescription::indexName))
                        .map(index -> fields("name", index.indexName(), "arn", index.indexArn(),
                            "keySchema", keys(index.keySchema()), "projection", projection(index.projection()))).toList());
                    if (table.streamSpecification() != null) details.put("stream", fields(
                        "enabled", table.streamSpecification().streamEnabled(), "viewType", table.streamSpecification().streamViewTypeAsString(),
                        "arn", table.latestStreamArn()));
                });
            }
            return page.lastEvaluatedTableName();
        }));
    }

    private List<Map<String, Object>> keys(List<KeySchemaElement> keys) {
        return keys.stream().sorted(Comparator.comparing(KeySchemaElement::keyTypeAsString))
            .map(key -> fields("name", key.attributeName(), "type", key.keyTypeAsString())).toList();
    }

    private Map<String, Object> projection(Projection projection) {
        return projection == null ? Map.of() : fields("type", projection.projectionTypeAsString(),
            "nonKeyAttributes", projection.nonKeyAttributes().stream().sorted().toList());
    }
}
