package io.github.gregwhitaker.awsemulator.aws.s3;

import org.gradle.api.GradleException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** S3 listing and removal semantics shared by the service's task types. */
final class S3BucketOperations {
    List<Bucket> list(S3Client client) {
        List<Bucket> buckets = new ArrayList<>();
        var tokens = new HashSet<String>();
        String token = null;
        do {
            var page = client.listBuckets(ListBucketsRequest.builder().maxBuckets(1000).continuationToken(token).build());
            buckets.addAll(page.buckets());
            token = page.continuationToken();
            if (token != null && !token.isEmpty() && !tokens.add(token)) {
                throw new GradleException("S3 bucket listing returned a repeated continuation token.");
            }
        } while (token != null && !token.isEmpty());
        return buckets;
    }

    void purge(S3Client client, String bucket) {
        var status = client.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build()).status();
        boolean versioned = status == BucketVersioningStatus.ENABLED || status == BucketVersioningStatus.SUSPENDED;
        List<ObjectIdentifier> previous = List.of();
        while (true) {
            List<ObjectIdentifier> objects = new ArrayList<>();
            if (versioned) {
                var page = client.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket).maxKeys(1000).build());
                page.versions().forEach(version -> objects.add(version(version.key(), version.versionId())));
                page.deleteMarkers().forEach(marker -> objects.add(version(marker.key(), marker.versionId())));
                if (objects.isEmpty() && Boolean.TRUE.equals(page.isTruncated())) {
                    throw new GradleException("S3 version listing made no progress for bucket '" + bucket + "'.");
                }
            } else {
                var page = client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).maxKeys(1000).build());
                page.contents().forEach(object -> objects.add(ObjectIdentifier.builder().key(object.key()).build()));
                if (objects.isEmpty() && Boolean.TRUE.equals(page.isTruncated())) {
                    throw new GradleException("S3 object listing made no progress for bucket '" + bucket + "'.");
                }
            }
            if (objects.isEmpty()) return;
            if (objects.equals(previous)) throw new GradleException("S3 purge made no progress for bucket '" + bucket + "'.");
            // Fetch the first remaining page after deletion: deleting items can invalidate offset-based tokens.
            for (int start = 0; start < objects.size(); start += 1000) {
                var batch = objects.subList(start, Math.min(start + 1000, objects.size()));
                var result = client.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket)
                    .delete(Delete.builder().objects(batch).quiet(true).build()).build());
                if (!result.errors().isEmpty()) {
                    var error = result.errors().get(0);
                    throw new GradleException("Could not purge S3 bucket '" + bucket + "': " + result.errors().size()
                        + " object deletions failed; " + error.code() + " for key '" + error.key() + "'.");
                }
            }
            previous = objects;
        }
    }

    void delete(S3Client client, String bucket, boolean force) {
        try {
            if (force) purge(client, bucket);
            client.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception error) {
            if (error.statusCode() == 404) return;
            if (!force && error.awsErrorDetails() != null && "BucketNotEmpty".equals(error.awsErrorDetails().errorCode())) {
                throw new GradleException("S3 bucket '" + bucket + "' is not empty. Set force to true to remove its contents.", error);
            }
            throw error;
        }
    }

    private ObjectIdentifier version(String key, String id) {
        if (id == null || id.isEmpty()) throw new GradleException("S3 returned an object version without a version ID for '" + key + "'.");
        return ObjectIdentifier.builder().key(key).versionId(id).build();
    }
}
