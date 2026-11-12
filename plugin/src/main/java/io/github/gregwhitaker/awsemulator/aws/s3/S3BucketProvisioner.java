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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.List;

/** Ensures a general-purpose bucket exists without recreating or changing an existing bucket. */
final class S3BucketProvisioner {
    static void validate(String name) {
        if (!name.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || name.contains("..")
                || name.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
                || List.of("xn--", "sthree-", "amzn-s3-demo-").stream().anyMatch(name::startsWith)
                || List.of("-s3alias", "--ol-s3", ".mrap", "--x-s3", "--table-s3", "-an").stream().anyMatch(name::endsWith)) {
            throw new GradleException("bucketName must be a valid general-purpose S3 bucket name (3–63 lowercase characters).");
        }
    }

    void provision(S3Client client, String name, String region) {
        validate(name);
        if (exists(client, name, region)) return;
        var request = CreateBucketRequest.builder().bucket(name);
        if (!region.equals("us-east-1")) request.createBucketConfiguration(config -> config.locationConstraint(region));
        try {
            client.createBucket(request.build());
        } catch (BucketAlreadyOwnedByYouException raced) {
            // Another task may have created the same bucket after the HEAD request.
            if (!exists(client, name, region)) throw raced;
        }
    }

    private boolean exists(S3Client client, String name, String region) {
        try {
            String actual = client.headBucket(HeadBucketRequest.builder().bucket(name).build()).bucketRegion();
            if (actual != null && !actual.isBlank() && !actual.equals(region)) {
                throw new GradleException("S3 bucket '" + name + "' already exists in " + actual + ", expected " + region + ".");
            }
            return true;
        } catch (S3Exception error) {
            if (error.statusCode() == 404) return false;
            throw error;
        }
    }
}
