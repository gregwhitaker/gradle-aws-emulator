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

package io.github.gregwhitaker.awsemulator.internal.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EmulatorReportWriterTest {
    @TempDir Path directory;

    @Test void bothFormatsPreservePartialResultsAndEscapeServiceProvidedMarkup() throws Exception {
        var section = new ReportSection("S3");
        String unsafe = "<script>alert('resource')</script>&\"";
        var configuration = section.resource("AWS::S3::Bucket", unsafe);
        configuration.put("versioning", "Enabled");
        var metadata = new java.util.ArrayList<String>();
        metadata.add("captured");
        configuration.put("metadata", metadata);
        section.read("GetBucketLocation", unsafe, () -> { throw S3Exception.builder().statusCode(501)
            .message("private-response-body").awsErrorDetails(AwsErrorDetails.builder().errorCode("NotImplemented").build()).build(); });
        var report = new EmulatorReport(1, "2026-11-14T10:00:00Z",
            new EmulatorReport.RuntimeInfo("default", "DEVELOPMENT", false, "https://localhost:9297", "us-east-1"),
            List.of(section.result(), new ReportSection("SQS").result()));
        configuration.put("versioning", "later-change");
        metadata.add("later-change");
        EmulatorReportWriter.write(report, directory);
        var json = new ObjectMapper().readTree(directory.resolve("report.json").toFile());
        assertEquals(1, json.path("schemaVersion").asInt());
        assertEquals("PARTIAL", json.path("services").get(0).path("status").asText());
        assertEquals(unsafe, json.path("services").get(0).path("resources").get(0).path("id").asText());
        assertEquals("UNSUPPORTED", json.path("services").get(0).path("issues").get(0).path("kind").asText());
        String html = Files.readString(directory.resolve("index.html"));
        assertTrue(html.contains("Incomplete snapshot"));
        assertTrue(html.contains("No resources found."));
        assertTrue(html.contains("&lt;script&gt;alert(&#39;resource&#39;)&lt;/script&gt;&amp;&quot;"));
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("private-response-body"));
        assertFalse(json.toString().contains("private-response-body"));
        assertFalse(json.toString().contains("later-change"));
        Files.writeString(directory.resolve("unrelated.txt"), "keep");
        EmulatorReportWriter.clear(directory);
        assertFalse(Files.exists(directory.resolve("index.html")));
        assertFalse(Files.exists(directory.resolve("report.json")));
        assertTrue(Files.exists(directory.resolve("unrelated.txt")));
    }

    @Test void failedAndEmptyServicesAreDistinctAndRepeatedPaginationTerminates() {
        var failed = new ReportSection("SNS");
        failed.read("ListTopics", null, () -> ReportSection.pages(token -> "repeated"));
        assertEquals("FAILED", failed.result().status());
        assertEquals("Repeated pagination token; collection stopped.", failed.result().issues().get(0).message());
        assertEquals("COMPLETE", new ReportSection("SNS").result().status());
        failed.resource("AWS::SNS::Topic", "last");
        failed.resource("AWS::SNS::Topic", "first");
        assertEquals("first", failed.result().resources().get(0).id());
        assertEquals("PARTIAL", failed.result().status());
    }
}
