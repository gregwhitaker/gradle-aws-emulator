package io.github.gregwhitaker.awsemulator.aws.cloudwatchlogs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gregwhitaker.awsemulator.aws.internal.*;
import org.gradle.api.GradleException;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;
import java.nio.file.Path;
import java.util.*;

final class CloudWatchLogsOperations {
    static String group(String name) {
        ServiceSupport.name(name, "[A-Za-z0-9_./#-]{1,512}", "logGroupName");
        if (name.startsWith("aws/")) throw new GradleException("Log group names cannot start with aws/.");
        return name;
    }
    static String stream(String name) { return ServiceSupport.name(name, "[^:*\\r\\n]{1,512}", "logStreamName"); }

    void createGroup(CloudWatchLogsClient client, String name) {
        try { client.createLogGroup(CreateLogGroupRequest.builder().logGroupName(name).build()); }
        catch (ResourceAlreadyExistsException ignored) { }
    }
    void createStream(CloudWatchLogsClient client, String group, String name) {
        try { client.createLogStream(CreateLogStreamRequest.builder().logGroupName(group).logStreamName(name).build()); }
        catch (ResourceAlreadyExistsException ignored) { }
    }
    List<String> groups(CloudWatchLogsClient client) {
        List<String> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); String token = null;
        do {
            var page = client.describeLogGroups(DescribeLogGroupsRequest.builder().nextToken(token).build());
            page.logGroups().forEach(g -> result.add(g.logGroupName())); token = ServiceSupport.next(page.nextToken(), seen);
        } while (token != null);
        return result;
    }
    List<Map<String, Object>> filter(CloudWatchLogsClient client, String group, String pattern, Long start, int max) {
        List<Map<String, Object>> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); String token = null;
        do {
            var page = client.filterLogEvents(FilterLogEventsRequest.builder().logGroupName(group).filterPattern(pattern)
                .startTime(start).nextToken(token).limit(Math.min(10000, max + 1)).build());
            for (var event : page.events()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("timestamp", event.timestamp()); row.put("message", event.message());
                row.put("logStreamName", event.logStreamName()); row.put("eventId", event.eventId()); result.add(row);
            }
            checkLimit(result.size(), max); token = ServiceSupport.next(page.nextToken(), seen);
        } while (token != null);
        return result;
    }
    List<Map<String, Object>> get(CloudWatchLogsClient client, String group, String stream, Long start, int max) {
        List<Map<String, Object>> result = new ArrayList<>(); Set<String> seen = new HashSet<>(); String token = null;
        do {
            var page = client.getLogEvents(GetLogEventsRequest.builder().logGroupName(group).logStreamName(stream).startTime(start)
                .startFromHead(true).nextToken(token).limit(Math.min(10000, max + 1)).build());
            for (var event : page.events()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("timestamp", event.timestamp()); row.put("message", event.message()); result.add(row);
            }
            checkLimit(result.size(), max);
            // GetLogEvents signals completion by returning the same forward token supplied in the request.
            if (Objects.equals(token, page.nextForwardToken())) return result;
            token = ServiceSupport.next(page.nextForwardToken(), seen);
        } while (token != null);
        return result;
    }
    void output(Path path, List<Map<String, Object>> events) {
        try { ResourceFiles.write(path, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(events)); }
        catch (java.io.IOException e) { throw new GradleException("Cannot serialize log events (values omitted)."); }
    }
    private void checkLimit(int count, int max) {
        if (count > max) throw new GradleException("Log results exceed maxEvents; narrow the filter/startTime or raise the limit.");
    }
}
