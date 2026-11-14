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

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import java.time.Instant;

/** Collects and renders a snapshot in an isolated JVM with Kinesis JSON wire format enabled. */
public abstract class EmulatorReportWork implements WorkAction<EmulatorReportWork.Parameters> {
    public interface Parameters extends WorkParameters {
        Property<String> getRuntimeName();
        Property<String> getMode();
        Property<Boolean> getExternal();
        Property<String> getEndpoint();
        Property<String> getRegion();
        DirectoryProperty getConnectionDirectory();
        RegularFileProperty getCredentialsFile();
        RegularFileProperty getCertificateFile();
        Property<Integer> getTimeoutSeconds();
        DirectoryProperty getReportDirectory();
        Property<Boolean> getFailOnError();
    }

    @Override public void execute() {
        var p = getParameters();
        var connection = new EmulatorConnection(p.getEndpoint().get(), p.getRegion().get(),
            p.getConnectionDirectory().get().getAsFile().toPath(), p.getCredentialsFile().get().getAsFile().toPath(),
            p.getCertificateFile().get().getAsFile().toPath());
        var metadata = new EmulatorReport.RuntimeInfo(p.getRuntimeName().get(), p.getMode().get(),
            p.getExternal().get(), connection.endpoint(), connection.region());
        var snapshot = new EmulatorReport(1, Instant.now().toString(), metadata,
            new EmulatorReportCollector().collect(new AwsClientFactory(connection, p.getTimeoutSeconds().get())));
        var directory = p.getReportDirectory().get().getAsFile().toPath();
        EmulatorReportWriter.write(snapshot, directory);
        var log = Logging.getLogger(EmulatorReportWork.class);
        for (var section : snapshot.services()) log.lifecycle("{}: {} resources ({})",
            section.service(), section.resources().size(), section.status());
        log.lifecycle("AWS Emulator HTML report: {}", directory.resolve("index.html").toUri());
        log.lifecycle("AWS Emulator JSON report: {}", directory.resolve("report.json"));
        if (!snapshot.complete()) {
            if (p.getFailOnError().get()) throw new GradleException("AWS Emulator report is incomplete; see the report's collection issues.");
            log.warn("AWS Emulator report is incomplete; see the report's collection issues.");
        }
    }
}
