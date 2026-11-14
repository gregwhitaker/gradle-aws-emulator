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


package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.gregwhitaker.awsemulator.aws.internal.FixtureFiles;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.nio.file.Path;
import java.util.*;

/** Paginated, deterministic fixture exports; no writes or table reconfiguration. */
final class DynamoDbItemExport {
    int export(DynamoDbClient client, String table, Path output) {
        var description = client.describeTable(DescribeTableRequest.builder().tableName(table).build()).table();
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        var fixtures = new DynamoDbItemFixtures();
        List<com.fasterxml.jackson.databind.node.ObjectNode> entries = new ArrayList<>();
        var budget = new FixtureFiles.Budget();
        Set<Map<String, AttributeValue>> tokens = new HashSet<>();
        Map<String, AttributeValue> token = null;
        do {
            var page = client.scan(ScanRequest.builder().tableName(table).consistentRead(true).limit(1000)
                .exclusiveStartKey(token).build());
            for (var item : page.items()) {
                var entry = fixtures.encode(item);
                budget.add(entry);
                entries.add(entry);
                items.add(item);
            }
            token = page.lastEvaluatedKey();
            if (token != null && !token.isEmpty() && !tokens.add(token)) {
                throw new GradleException("DynamoDB export returned a repeated continuation key (values omitted).");
            }
        } while (token != null && !token.isEmpty());
        new DynamoDbItemOperations().validate(items, description);
        // Scans and maps have no stable ordering. Sort the canonical JSON entries for reproducible artifacts.
        entries.sort(Comparator.comparing(Object::toString));
        var root = JsonNodeFactory.instance.arrayNode();
        entries.forEach(root::add);
        FixtureFiles.write(output, root);
        return entries.size();
    }
}
