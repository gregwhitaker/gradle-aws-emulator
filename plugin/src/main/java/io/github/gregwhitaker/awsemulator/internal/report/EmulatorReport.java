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

import java.util.List;

/** Versioned, execution-time inventory shared by the HTML and JSON renderers. */
public record EmulatorReport(int schemaVersion, String capturedAt, RuntimeInfo runtime, List<ReportSection.Result> services) {
    public EmulatorReport { services = List.copyOf(services); }

    /** Connection metadata only; credentials and TLS file contents are never included. */
    public record RuntimeInfo(String name, String mode, boolean external, String endpoint, String region) {}

    public boolean complete() { return services.stream().allMatch(section -> section.status().equals("COMPLETE")); }
}
