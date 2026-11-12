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

package io.github.gregwhitaker.awsemulator.aws.sns;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SnsQueuePolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOPIC = "arn:aws:sns:us-east-1:123456789012:events";
    private static final String QUEUE = "arn:aws:sqs:us-east-1:123456789012:subscriber";

    @Test
    void createsTopicScopedGrantAndLeavesRepeatedProvisioningUnchanged() throws Exception {
        String policy = SnsQueuePolicy.grant(null, TOPIC, QUEUE);
        var statement = JSON.readTree(policy).path("Statement").get(0);
        assertEquals("sns.amazonaws.com", statement.path("Principal").path("Service").asText());
        assertEquals("sqs:SendMessage", statement.path("Action").asText());
        assertEquals(QUEUE, statement.path("Resource").asText());
        assertEquals(TOPIC, statement.path("Condition").path("ArnEquals").path("aws:SourceArn").asText());
        assertEquals(policy, SnsQueuePolicy.grant(policy, TOPIC, QUEUE));
    }

    @Test
    void preservesSingleStatementPoliciesAndGrantsForOtherTopics() throws Exception {
        String original = """
            {"Version":"2012-10-17","Id":"retain-me","Statement":{"Sid":"Existing","Effect":"Deny",
             "Principal":"*","Action":"sqs:DeleteQueue","Resource":"*"}}
            """;
        String first = SnsQueuePolicy.grant(original, TOPIC, QUEUE);
        String second = SnsQueuePolicy.grant(first, TOPIC + "-other", QUEUE);
        var parsed = JSON.readTree(second);
        assertEquals("retain-me", parsed.path("Id").asText());
        assertEquals(3, parsed.path("Statement").size());
        assertEquals(JSON.readTree(original).get("Statement"), parsed.get("Statement").get(0));
        assertEquals(second, SnsQueuePolicy.grant(second, TOPIC, QUEUE));
    }

    @Test
    void refusesMalformedPoliciesInsteadOfReplacingPermissions() {
        for (String policy : List.of("secret-invalid-json", "[]", "{\"Statement\":null}", "{\"Statement\":[1]}",
            "{\"Statement\":[],\"Statement\":[]}", "{} {}")) {
            var error = assertThrows(GradleException.class, () -> SnsQueuePolicy.grant(policy, TOPIC, QUEUE));
            assertTrue(error.getMessage().contains("Existing permissions were not changed"));
            assertFalse(error.getMessage().contains("secret-invalid-json"));
        }
    }

    @Test
    void refusesToOverwriteAConflictingStatementId() {
        String existing = SnsQueuePolicy.grant(null, TOPIC, QUEUE).replace("sqs:SendMessage", "sqs:DeleteQueue");
        assertTrue(assertThrows(GradleException.class, () -> SnsQueuePolicy.grant(existing, TOPIC, QUEUE))
            .getMessage().contains("conflicting SNS delivery statement"));
    }
}
