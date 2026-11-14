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
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.junit.jupiter.api.Assertions.*;

class DynamoDbReportCollectorTest {
    @Test void followsPagesAndDescribesSchemaWithoutScanningTableData() {
        var client = SdkStub.client(DynamoDbClient.class, (proxy, method, args) -> switch (method.getName()) {
            case "listTables" -> {
                String token = ((ListTablesRequest) args[0]).exclusiveStartTableName();
                assertTrue(token == null || token.equals("one"));
                yield ListTablesResponse.builder().tableNames(token == null ? "one" : "two").lastEvaluatedTableName(token == null ? "one" : null).build();
            }
            case "describeTable" -> DescribeTableResponse.builder().table(TableDescription.builder()
                .tableName(((DescribeTableRequest) args[0]).tableName()).tableStatus(TableStatus.ACTIVE).itemCount(3L)
                .keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
                .globalSecondaryIndexes(GlobalSecondaryIndexDescription.builder().indexName("byEmail")
                    .keySchema(KeySchemaElement.builder().attributeName("email").keyType(KeyType.HASH).build())
                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build()).build()).build()).build();
            default -> throw new AssertionError("Unexpected or data-reading call: " + method.getName());
        });
        var section = new ReportSection("DynamoDB");
        new DynamoDbReportCollector().collect(client, section);
        assertEquals("COMPLETE", section.result().status());
        assertEquals(2, section.result().resources().size());
        var config = section.result().resources().get(0).configuration();
        assertEquals(3L, config.get("itemCount"));
        assertTrue(config.get("keySchema").toString().contains("HASH"));
        assertTrue(config.get("globalSecondaryIndexes").toString().contains("byEmail"));
    }
}
