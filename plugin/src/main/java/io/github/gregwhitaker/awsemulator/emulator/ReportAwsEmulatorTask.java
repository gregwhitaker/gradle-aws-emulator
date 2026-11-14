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

package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.internal.report.EmulatorReport;
import io.github.gregwhitaker.awsemulator.internal.report.EmulatorReportCollector;
import io.github.gregwhitaker.awsemulator.internal.report.EmulatorReportWriter;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import java.time.Instant;

/**
 * Captures S3, SQS, SNS, DynamoDB, and CloudFormation metadata from an existing emulator.
 * Writes index.html and report.json, including collection errors. Never starts an owned runtime.
 * Custom tasks default to MANAGED; the reportAwsEmulator convenience tasks use DEVELOPMENT.
 */
@DisableCachingByDefault(because = "Reads live emulator state on every invocation")
public abstract class ReportAwsEmulatorTask extends AbstractAwsResourceTask {
    public ReportAwsEmulatorTask() { getFailOnError().convention(false); }

    /** Output directory; defaults to build/reports/aws-emulator/runtime/mode/taskName. */
    @OutputDirectory
    public abstract DirectoryProperty getReportDirectory();

    /** Fail after writing both reports if any service could not be fully inspected. Defaults to false. */
    @Input
    public abstract Property<Boolean> getFailOnError();

    @Option(option = "fail-on-error", description = "Fail after writing reports if any service query fails")
    public void setFailOnErrorOption(boolean value) { getFailOnError().set(value); }

    @TaskAction
    public void report() {
        if (getApiCallTimeoutSeconds().get() < 1) throw new GradleException("apiCallTimeoutSeconds must be positive.");
        var directory = getReportDirectory().get().getAsFile().toPath();
        EmulatorReportWriter.clear(directory);
        var runtime = getRuntime().get();
        var connection = runtime.inspectionConnection(getMode().get());
        var metadata = new EmulatorReport.RuntimeInfo(getRuntimeName().get(), getMode().get().name(),
            runtime.getParameters().getExternalEnabled().getOrElse(false), connection.endpoint(), connection.region());
        var snapshot = new EmulatorReport(1, Instant.now().toString(), metadata,
            new EmulatorReportCollector().collect(new AwsClientFactory(connection, getApiCallTimeoutSeconds().get())));
        EmulatorReportWriter.write(snapshot, directory);
        for (var section : snapshot.services()) getLogger().lifecycle("{}: {} resources ({})",
            section.service(), section.resources().size(), section.status());
        getLogger().lifecycle("AWS Emulator HTML report: {}", directory.resolve("index.html").toUri());
        getLogger().lifecycle("AWS Emulator JSON report: {}", directory.resolve("report.json"));
        if (!snapshot.complete()) {
            if (getFailOnError().get()) throw new GradleException("AWS Emulator report is incomplete; see the report's collection issues.");
            getLogger().warn("AWS Emulator report is incomplete; see the report's collection issues.");
        }
    }
}
