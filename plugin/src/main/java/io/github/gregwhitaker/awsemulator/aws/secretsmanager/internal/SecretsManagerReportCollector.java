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

package io.github.gregwhitaker.awsemulator.aws.secretsmanager.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.ListSecretsRequest;
import static io.github.gregwhitaker.awsemulator.internal.report.ReportSection.*;

/** Inventories secret identifiers and rotation metadata without retrieving secret values. */
public final class SecretsManagerReportCollector {
    public void collect(SecretsManagerClient client, ReportSection section) {
        section.read("ListSecrets", null, () -> pages(token -> {
            var page = client.listSecrets(ListSecretsRequest.builder().maxResults(100).nextToken(token).build());
            for (var secret : page.secretList()) section.resource("AWS::SecretsManager::Secret", secret.arn()).putAll(fields(
                "name", secret.name(), "arn", secret.arn(), "kmsKeyId", secret.kmsKeyId(),
                "rotationEnabled", secret.rotationEnabled(), "rotationLambdaArn", secret.rotationLambdaARN(),
                "primaryRegion", secret.primaryRegion(), "owningService", secret.owningService(),
                "createdAt", timestamp(secret.createdDate()), "changedAt", timestamp(secret.lastChangedDate()),
                "rotatedAt", timestamp(secret.lastRotatedDate()), "deletedAt", timestamp(secret.deletedDate())));
            return page.nextToken();
        }));
    }
}
