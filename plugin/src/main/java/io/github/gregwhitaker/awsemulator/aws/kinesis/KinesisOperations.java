package io.github.gregwhitaker.awsemulator.aws.kinesis;

import io.github.gregwhitaker.awsemulator.aws.internal.ServiceSupport;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.*;
import java.util.*;

final class KinesisOperations {
    static final String NAME = "[A-Za-z0-9_.-]{1,128}";
    void create(KinesisClient client, String name, int shards, int seconds) {
        try {
            var existing = client.describeStreamSummary(DescribeStreamSummaryRequest.builder().streamName(name).build()).streamDescriptionSummary();
            if (existing.streamModeDetails() != null && existing.streamModeDetails().streamMode() != StreamMode.PROVISIONED
                    || existing.openShardCount() != shards) throw new GradleException("Existing Kinesis stream configuration differs; reshard or delete it explicitly.");
        } catch (ResourceNotFoundException missing) {
            client.createStream(CreateStreamRequest.builder().streamName(name).shardCount(shards)
                .streamModeDetails(StreamModeDetails.builder().streamMode(StreamMode.PROVISIONED).build()).build());
        }
        ServiceSupport.await("waiting for Kinesis stream " + name, seconds, () -> {
            var state = client.describeStreamSummary(DescribeStreamSummaryRequest.builder().streamName(name).build()).streamDescriptionSummary().streamStatus();
            if (state == StreamStatus.DELETING) throw new GradleException("Kinesis stream is being deleted: " + name);
            return state == StreamStatus.ACTIVE;
        });
    }
    List<String> list(KinesisClient client) {
        List<String> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); String start = null;
        while (true) {
            var page = client.listStreams(ListStreamsRequest.builder().exclusiveStartStreamName(start).limit(100).build());
            result.addAll(page.streamNames());
            if (!Boolean.TRUE.equals(page.hasMoreStreams())) return result;
            if (page.streamNames().isEmpty()) throw new GradleException("Kinesis listing made no progress.");
            start = ServiceSupport.next(page.streamNames().get(page.streamNames().size() - 1), seen);
        }
    }
    void delete(KinesisClient client, String name, int seconds) {
        try {
            var state = client.describeStreamSummary(DescribeStreamSummaryRequest.builder().streamName(name).build()).streamDescriptionSummary().streamStatus();
            if (state != StreamStatus.DELETING) client.deleteStream(DeleteStreamRequest.builder().streamName(name).build());
        } catch (ResourceNotFoundException missing) { return; }
        ServiceSupport.await("deleting Kinesis stream " + name, seconds, () -> {
            try { client.describeStreamSummary(DescribeStreamSummaryRequest.builder().streamName(name).build()); return false; }
            catch (ResourceNotFoundException missing) { return true; }
        });
    }
    void publish(KinesisClient client, String name, List<PutRecordsRequestEntry> records) {
        int offset = 0;
        while (offset < records.size()) {
            int end = offset, bytes = 0;
            while (end < records.size() && end - offset < 500) {
                var record = records.get(end);
                int size = record.data().asByteArray().length + record.partitionKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (size > 1024 * 1024) throw new GradleException("Kinesis fixture exceeds the supported 1 MiB record limit.");
                if (bytes + size > 5 * 1024 * 1024) break;
                bytes += size; end++;
            }
            var response = client.putRecords(PutRecordsRequest.builder().streamName(name).records(records.subList(offset, end)).build());
            if (response.records().size() != end - offset) throw new GradleException("Kinesis returned an incomplete publish result.");
            List<Integer> failed = new ArrayList<>();
            for (int i = 0; i < response.records().size(); i++) if (response.records().get(i).errorCode() != null) failed.add(offset + i + 1);
            if (!failed.isEmpty() || response.failedRecordCount() != null && response.failedRecordCount() > 0) {
                throw new GradleException("Kinesis publish partially failed at fixture records " + failed + ". Successful records were retained; rerunning can duplicate them.");
            }
            offset = end;
        }
    }
}
