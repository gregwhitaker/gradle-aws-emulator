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
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.gregwhitaker.awsemulator.aws.internal.ResourceFiles;
import org.gradle.api.GradleException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Standalone HTML and versioned JSON, with all service-provided HTML text escaped. */
public final class EmulatorReportWriter {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private EmulatorReportWriter() {}

    /** Removes this task's previous artifacts so a connection failure cannot leave a stale successful report. */
    public static void clear(Path directory) {
        try {
            Files.deleteIfExists(directory.resolve("index.html"));
            Files.deleteIfExists(directory.resolve("report.json"));
        } catch (IOException e) { throw new GradleException("Cannot remove previous AWS Emulator reports.", e); }
    }

    public static void write(EmulatorReport report, Path directory) {
        try {
            byte[] json = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
            byte[] html = html(report).getBytes(StandardCharsets.UTF_8);
            ResourceFiles.write(directory.resolve("report.json"), json);
            ResourceFiles.write(directory.resolve("index.html"), html);
        } catch (IOException e) { throw new GradleException("Cannot serialize AWS Emulator report.", e); }
    }

    static String html(EmulatorReport report) {
        var html = new StringBuilder("""
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>AWS Emulator report</title><style>
            :root{color-scheme:light dark;--bg:#f4f6fa;--surface:#fff;--ink:#182638;--muted:#526176;--line:#dce2eb;--accent:#075c90;--warn:#8b4500}
            @media(prefers-color-scheme:dark){:root{--bg:#111820;--surface:#1b2633;--ink:#e7edf5;--muted:#aebbc9;--line:#354455;--accent:#81cdfa;--warn:#ffbe80}}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.55 system-ui,sans-serif}
            main{max-width:1200px;margin:auto;padding:40px 24px}h1{font-size:32px;letter-spacing:-.7px;margin:6px 0}h2{font-size:23px;margin:0}
            h3{font-size:17px}a{color:var(--accent)}.eyebrow{color:var(--accent);font-size:12px;letter-spacing:2px;font-weight:700}
            .muted,small{color:var(--muted)}.runtime,section{background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:24px;margin-top:24px}
            .runtime dl{display:grid;grid-template-columns:140px minmax(0,1fr);gap:8px 16px;margin:0}.runtime dt{font-weight:600}.runtime dd{margin:0;overflow-wrap:anywhere}
            nav{display:flex;flex-wrap:wrap;gap:10px;margin-top:24px}nav a{padding:7px 12px;border:1px solid var(--line);border-radius:7px;text-decoration:none}
            .section-head{display:flex;align-items:center;flex-wrap:wrap;gap:12px}.badge{font-size:12px;font-weight:700;border:1px solid var(--line);border-radius:20px;padding:3px 10px}
            .issue,.incomplete{color:var(--warn)}table{border-collapse:collapse;width:100%;margin-top:18px}th,td{text-align:left;vertical-align:top;border-bottom:1px solid var(--line);padding:12px 10px}
            th{font-size:12px;text-transform:uppercase;letter-spacing:.5px;color:var(--muted)}td{overflow-wrap:anywhere}td:first-child{width:42%}
            code{font:13px/1.6 ui-monospace,monospace;overflow-wrap:anywhere;white-space:pre-wrap}summary{cursor:pointer;color:var(--accent);font-weight:600}
            details>dl{margin-top:14px}dl.metadata{margin:8px 0}dl.metadata dt{color:var(--muted);font-size:12px;font-weight:600;margin-top:9px}
            dl.metadata dd{margin:2px 0 0 12px}ul.values{margin:4px 0;padding-left:18px}ul.values>li{margin:8px 0}.issues{padding-left:22px}
            footer{margin-top:28px;color:var(--muted);font-size:13px}a:focus-visible,summary:focus-visible{outline:3px solid var(--accent);outline-offset:4px}
            @media(max-width:650px){main{padding:24px 12px}.runtime,section{padding:16px}.runtime dl{grid-template-columns:1fr;gap:2px}.runtime dd{margin-bottom:10px}th,td{padding:10px 5px}td:first-child{width:38%}h1{font-size:27px}}
            @media print{details>dl{display:block!important}body{background:white;color:black}main{max-width:none}nav{display:none}section{break-inside:avoid}}
            </style></head><body><main><header><div class="eyebrow">AWS EMULATOR / INVENTORY</div><h1>Emulator configuration</h1>
            """);
        html.append("<p class=\"muted\">Captured ").append(escape(report.capturedAt())).append(" · <a href=\"report.json\">Download JSON</a></p>");
        if (!report.complete()) html.append("<p class=\"incomplete\"><strong>Incomplete snapshot.</strong> Some queries failed. Review the collection issues in each section.</p>");
        html.append("</header><div class=\"runtime\"><dl>");
        var runtime = report.runtime();
        for (var entry : ReportSection.fields("Runtime", runtime.name(), "Mode", runtime.mode(), "Connection",
                runtime.external() ? "External" : "Plugin-owned", "Endpoint", runtime.endpoint(), "Region", runtime.region()).entrySet()) {
            html.append("<dt>").append(escape(entry.getKey())).append("</dt><dd>").append(escape(entry.getValue())).append("</dd>");
        }
        html.append("</dl></div><nav aria-label=\"Services\">");
        for (var section : report.services()) html.append("<a href=\"#").append(escape(section.service())).append("\">")
            .append(escape(section.service())).append(" <span class=\"muted\">(").append(section.resources().size()).append(")</span></a>");
        html.append("</nav>");
        for (var section : report.services()) {
            html.append("<section id=\"").append(escape(section.service())).append("\"><div class=\"section-head\"><h2>")
                .append(escape(section.service())).append("</h2><span class=\"badge\">").append(escape(section.status())).append("</span></div>");
            html.append("<p class=\"muted\">").append(section.resources().size()).append(" resources collected</p>");
            if (section.resources().isEmpty()) html.append(section.status().equals("COMPLETE")
                ? "<p>No resources found.</p>" : "<p>Resource inventory could not be completed.</p>");
            else {
                html.append("<table><thead><tr><th scope=\"col\">Resource</th><th scope=\"col\">Configuration</th></tr></thead><tbody>");
                for (var resource : section.resources()) {
                    html.append("<tr><td><code>").append(escape(resource.id())).append("</code><br><small>")
                        .append(escape(resource.type())).append("</small></td><td><details><summary>View configuration</summary>");
                    value(html, resource.configuration());
                    html.append("</details></td></tr>");
                }
                html.append("</tbody></table>");
            }
            if (!section.issues().isEmpty()) {
                html.append("<h3 class=\"issue\">Collection issues</h3><ul class=\"issues\">");
                for (var issue : section.issues()) html.append("<li><strong>").append(escape(issue.kind())).append(" · ")
                    .append(escape(issue.operation())).append("</strong>")
                    .append(issue.resource() == null ? "" : " — <code>" + escape(issue.resource()) + "</code>")
                    .append("<br>").append(escape(issue.message())).append("</li>");
                html.append("</ul>");
            }
            html.append("</section>");
        }
        return html.append("<footer>Live metadata snapshot; resources may change during collection. Counts are service-reported and may be approximate. "
            + "Object contents, message bodies, table items, credentials, and CloudFormation parameter/output values are omitted. "
            + "SNS subscription endpoints are shown for SQS and Lambda only. JSON schema version ")
            .append(report.schemaVersion()).append(".</footer></main></body></html>").toString();
    }

    private static void value(StringBuilder html, Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            html.append("<dl class=\"metadata\">");
            for (var entry : map.entrySet()) {
                html.append("<dt>").append(escape(entry.getKey())).append("</dt><dd>");
                value(html, entry.getValue());
                html.append("</dd>");
            }
            html.append("</dl>");
        } else if (value instanceof List<?> list && !list.isEmpty()) {
            html.append("<ul class=\"values\">");
            for (var item : list) { html.append("<li>"); value(html, item); html.append("</li>"); }
            html.append("</ul>");
        } else html.append("<code>").append(escape(value)).append("</code>");
    }

    private static String escape(Object value) {
        return String.valueOf(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
