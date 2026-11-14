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

package io.github.gregwhitaker.awsemulator.aws.s3;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class S3BucketConfigurationTest {
    @TempDir Path directory;

    @Test
    void readsPolicyFilesAtExecutionAndRejectsMalformedOrOversizedDocumentsWithoutEchoingValues() throws Exception {
        Path policy = directory.resolve("policy.json");
        Files.writeString(policy, "{\"Statement\": []}");
        assertEquals("{\"Statement\":[]}", S3BucketConfiguration.policyFile(policy));
        Files.writeString(policy, "{\"Version\":\"2012-10-17\",\"Statement\":[]}");
        assertTrue(S3BucketConfiguration.policyFile(policy).contains("Version"));
        for (String invalid : List.of("", "[]", "{\"secret\":broken}", "{\"secret\":1,\"secret\":2}", "{} {}")) {
            var failure = assertThrows(GradleException.class, () -> S3BucketConfiguration.policy(invalid));
            assertFalse(failure.getMessage().contains("secret"));
            assertNull(failure.getCause());
        }
        String large = "{\"secret\":\"" + "é".repeat(11_000) + "\"}";
        assertTrue(assertThrows(GradleException.class, () -> S3BucketConfiguration.policy(large)).getMessage().contains("20 KiB"));
        Files.writeString(policy, large);
        assertThrows(GradleException.class, () -> S3BucketConfiguration.policyFile(policy));
        assertThrows(GradleException.class, () -> S3BucketConfiguration.policyFile(directory.resolve("absent")));
        Files.write(policy, new byte[] {(byte) 0xc3, (byte) 0x28});
        assertThrows(GradleException.class, () -> S3BucketConfiguration.policyFile(policy));
    }

    @Test
    void versioningRequiresAnExplicitKnownState() {
        assertEquals(BucketVersioningStatus.ENABLED, S3BucketConfiguration.versioning(BucketVersioningStatus.ENABLED));
        assertEquals(BucketVersioningStatus.SUSPENDED, S3BucketConfiguration.versioning(BucketVersioningStatus.SUSPENDED));
        assertThrows(GradleException.class, () -> S3BucketConfiguration.versioning(BucketVersioningStatus.UNKNOWN_TO_SDK_VERSION));
    }

    @Test
    void acceptsAllDestinationKindsFiltersAndAnExplicitEmptyConfiguration() {
        var configuration = NotificationConfiguration.builder()
            .queueConfigurations(queue().id("queue").filter(f -> f.key(k -> k.filterRules(
                FilterRule.builder().name(FilterRuleName.PREFIX).value("uploads/").build(),
                FilterRule.builder().name(FilterRuleName.SUFFIX).value(".json").build()))).build())
            .topicConfigurations(t -> t.id("topic").topicArn("arn:aws:sns:us-east-1:000000000000:uploads").events(Event.S3_OBJECT_REMOVED))
            .lambdaFunctionConfigurations(f -> f.id("function").lambdaFunctionArn("arn:aws:lambda:us-east-1:000000000000:function:handler:$LATEST")
                .events(Event.S3_OBJECT_CREATED_PUT))
            .eventBridgeConfiguration(EventBridgeConfiguration.builder().build()).build();
        assertDoesNotThrow(() -> S3BucketConfiguration.notifications(configuration));
        assertDoesNotThrow(() -> S3BucketConfiguration.notifications(NotificationConfiguration.builder().build()));
        var normalized = S3BucketConfiguration.notifications(configuration);
        assertEquals(List.of("prefix", "suffix"), normalized.queueConfigurations().get(0).filter().key().filterRules().stream()
            .map(FilterRule::nameAsString).toList());
        assertEquals("Prefix", configuration.queueConfigurations().get(0).filter().key().filterRules().get(0).nameAsString());
        assertEquals(configuration.eventBridgeConfiguration(), normalized.eventBridgeConfiguration());
        assertDoesNotThrow(() -> S3BucketConfiguration.notifications(normalized));
    }

    @Test
    void rejectsMissingOrWrongDestinationsAndEvents() {
        for (QueueConfiguration invalid : List.of(
            queue().queueArn((String) null).build(), queue().queueArn("arn:aws:sns:us-east-1:000000000000:uploads").build(),
            queue().queueArn("arn:aws:sqs:us-east-1:000000000000:uploads.fifo").build(),
            queue().events(List.<Event>of()).build(), queue().eventsWithStrings("s3:Unrecognized:*").build())) {
            assertThrows(GradleException.class, () -> S3BucketConfiguration.notifications(
                NotificationConfiguration.builder().queueConfigurations(invalid).build()));
        }
    }

    @Test
    void rejectsDuplicateIdsAndInvalidFiltersBeforeAnyRequest() {
        assertThrows(GradleException.class, () -> S3BucketConfiguration.notifications(NotificationConfiguration.builder()
            .queueConfigurations(queue().id("duplicate").build())
            .topicConfigurations(t -> t.id("duplicate").topicArn("arn:aws:sns:us-east-1:000000000000:uploads").events(Event.S3_OBJECT_CREATED)).build()));
        for (List<FilterRule> invalid : List.of(
            List.of(FilterRule.builder().name("unknown").value("x").build()),
            List.of(FilterRule.builder().name(FilterRuleName.PREFIX).build()),
            List.of(FilterRule.builder().name(FilterRuleName.PREFIX).value("a").build(),
                FilterRule.builder().name("prefix").value("b").build()))) {
            assertThrows(GradleException.class, () -> S3BucketConfiguration.notifications(NotificationConfiguration.builder()
                .queueConfigurations(queue().filter(f -> f.key(k -> k.filterRules(invalid))).build()).build()));
        }
    }

    private QueueConfiguration.Builder queue() {
        return QueueConfiguration.builder().queueArn("arn:aws:sqs:us-east-1:000000000000:uploads").events(Event.S3_OBJECT_CREATED);
    }
}
