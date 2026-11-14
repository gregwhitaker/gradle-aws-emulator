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

import io.github.gregwhitaker.awsemulator.aws.internal.JsonFixtures;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.s3.model.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates bucket configuration before acquiring a runtime; policy values never appear in diagnostics. */
final class S3BucketConfiguration {
    private static final int MAX_POLICY_BYTES = 20 * 1024;

    private S3BucketConfiguration() {}

    static String policyFile(Path file) {
        try {
            if (Files.size(file) > MAX_POLICY_BYTES) throw new GradleException("S3 bucket policy exceeds 20 KiB.");
            return policy(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GradleException("Cannot read UTF-8 S3 bucket policy file (values omitted).");
        }
    }

    static String policy(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_POLICY_BYTES) throw new GradleException("S3 bucket policy exceeds 20 KiB.");
        return JsonFixtures.object(value, "S3 bucket policy");
    }

    static BucketVersioningStatus versioning(BucketVersioningStatus status) {
        if (status != BucketVersioningStatus.ENABLED && status != BucketVersioningStatus.SUSPENDED) {
            throw new GradleException("status must be ENABLED or SUSPENDED.");
        }
        return status;
    }

    static void notifications(NotificationConfiguration configuration) {
        var ids = new HashSet<String>();
        configuration.queueConfigurations().forEach(queue ->
            destination(ids, queue.id(), queue.queueArn(), "sqs", queue.events(), queue.filter()));
        configuration.topicConfigurations().forEach(topic ->
            destination(ids, topic.id(), topic.topicArn(), "sns", topic.events(), topic.filter()));
        configuration.lambdaFunctionConfigurations().forEach(function ->
            destination(ids, function.id(), function.lambdaFunctionArn(), "lambda", function.events(), function.filter()));
    }

    private static void destination(Set<String> ids, String id, String arn, String service, List<Event> events, NotificationConfigurationFilter filter) {
        if (id != null && (id.isBlank() || !ids.add(id))) throw new GradleException("Notification IDs must be nonblank and unique.");
        String resource = service.equals("lambda") ? "function:[A-Za-z0-9_-]+(?::[A-Za-z0-9_$-]+)?" : "[A-Za-z0-9_-]+";
        if (arn == null || !arn.matches("arn:[a-z0-9-]+:" + service + ":[a-z0-9-]+:[0-9]{12}:" + resource)) {
            throw new GradleException("Notification destination must be a " + service + " ARN (SQS/SNS FIFO destinations are not supported).");
        }
        if (events.isEmpty() || events.contains(Event.UNKNOWN_TO_SDK_VERSION)) {
            throw new GradleException("Notification destinations require at least one recognized S3 event.");
        }
        if (filter != null && filter.key() != null) {
            var names = new HashSet<FilterRuleName>();
            for (FilterRule rule : filter.key().filterRules()) {
                if (rule.name() == null || rule.name() == FilterRuleName.UNKNOWN_TO_SDK_VERSION || !names.add(rule.name()) || rule.value() == null) {
                    throw new GradleException("Notification filters accept at most one prefix and one suffix, each with a value.");
                }
            }
        }
    }
}
