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

package io.github.gregwhitaker.awsemulator.aws.ssm.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DescribeParametersRequest;
import static io.github.gregwhitaker.awsemulator.internal.report.ReportSection.*;

/** Inventories parameter metadata without reading or decrypting parameter values. */
public final class SsmReportCollector {
    public void collect(SsmClient client, ReportSection section) {
        section.read("DescribeParameters", null, () -> pages(token -> {
            var page = client.describeParameters(DescribeParametersRequest.builder().maxResults(50).nextToken(token).build());
            for (var parameter : page.parameters()) section.resource("AWS::SSM::Parameter", parameter.name()).putAll(fields(
                "name", parameter.name(), "type", parameter.typeAsString(), "tier", parameter.tierAsString(),
                "dataType", parameter.dataType(), "version", parameter.version(), "keyId", parameter.keyId(),
                "modifiedAt", timestamp(parameter.lastModifiedDate())));
            return page.nextToken();
        }));
    }
}
