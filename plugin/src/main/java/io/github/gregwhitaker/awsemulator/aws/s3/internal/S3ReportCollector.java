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

package io.github.gregwhitaker.awsemulator.aws.s3.internal;

import io.github.gregwhitaker.awsemulator.internal.report.ReportSection;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import static io.github.gregwhitaker.awsemulator.internal.report.ReportSection.*;

/** Reads bucket metadata without listing or downloading object contents. */
public final class S3ReportCollector {
    public void collect(S3Client client, ReportSection section) {
        section.read("ListBuckets", null, () -> pages(token -> {
            var page = client.listBuckets(ListBucketsRequest.builder().maxBuckets(1000).continuationToken(token).build());
            for (var bucket : page.buckets()) {
                String name = bucket.name();
                var details = section.resource("AWS::S3::Bucket", name);
                details.putAll(fields("arn", "arn:aws:s3:::" + name, "createdAt",
                    bucket.creationDate() == null ? null : bucket.creationDate().toString()));
                section.read("GetBucketLocation", name, () -> {
                    String region = client.getBucketLocation(GetBucketLocationRequest.builder().bucket(name).build()).locationConstraintAsString();
                    details.put("region", region == null || region.isEmpty() ? "us-east-1" : region.equals("EU") ? "eu-west-1" : region);
                });
                section.read("GetBucketVersioning", name, () -> {
                    var versioning = client.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(name).build());
                    details.put("versioning", versioning.statusAsString() == null ? "Unversioned" : versioning.statusAsString());
                    details.putAll(fields("mfaDelete", versioning.mfaDeleteAsString()));
                });
            }
            return page.continuationToken();
        }));
    }
}
