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

package io.github.gregwhitaker.awsemulator.aws.eventbridge;

import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.*;
import org.gradle.api.GradleException;
import org.gradle.api.provider.*;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import java.util.List;
import java.util.Map;

/** Adds standard SQS targets by ID and ARN; unrelated target IDs are preserved. Queue policies are configured separately. */
@DisableCachingByDefault(because = "Uses live emulator resources")
@AwsEmulatorSetupTask
public abstract class PutEventBridgeTargetsTask extends AbstractAwsResourceTask {
    public PutEventBridgeTargetsTask() { getOverwrite().convention(false); }
    @Input public abstract Property<String> getEventBusName();
    @Option(option = "eventBusName", description = "EventBusName") public void setEventBusNameOption(String value) { getEventBusName().set(value); }
    @Input public abstract Property<String> getRuleName();
    @Option(option = "ruleName", description = "RuleName") public void setRuleNameOption(String value) { getRuleName().set(value); }
    @Input public abstract MapProperty<String, String> getTargets();
    @Input public abstract Property<Boolean> getOverwrite();
    @TaskAction
    public void executeTask() {
        String bus = ServiceSupport.name(getEventBusName().get(), EventBridgeOperations.BUS, "eventBusName");
        String rule = ServiceSupport.name(getRuleName().get(), EventBridgeOperations.RULE, "ruleName");
        var targets = getTargets().get();
        if (targets.isEmpty()) throw new GradleException("targets must not be empty.");
        targets.forEach((id, arn) -> {
            ServiceSupport.name(id, "[A-Za-z0-9_.-]{1,64}", "target ID");
            ServiceSupport.name(arn, "arn:[^:]+:sqs:[^:]+:[0-9]{12}:[A-Za-z0-9_-]{1,80}", "standard SQS target ARN");
        });
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).eventBridge()) {
            new EventBridgeOperations().putTargets(client, bus, rule, targets, getOverwrite().get());
        }
    }
}
