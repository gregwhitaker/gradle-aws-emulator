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

/** Creates an enabled event-pattern rule; conflicting rules require overwrite=true. */
@DisableCachingByDefault(because = "Uses live emulator resources")
@AwsEmulatorSetupTask
public abstract class PutEventBridgeRuleTask extends AbstractAwsResourceTask {
    public PutEventBridgeRuleTask() { getOverwrite().convention(false); }
    @Input public abstract Property<String> getEventBusName();
    @Option(option = "eventBusName", description = "EventBusName") public void setEventBusNameOption(String value) { getEventBusName().set(value); }
    @Input public abstract Property<String> getRuleName();
    @Option(option = "ruleName", description = "RuleName") public void setRuleNameOption(String value) { getRuleName().set(value); }
    @Input public abstract Property<String> getEventPattern();
    @Input public abstract Property<Boolean> getOverwrite();
    @TaskAction
    public void executeTask() {
        String bus = ServiceSupport.name(getEventBusName().get(), EventBridgeOperations.BUS, "eventBusName");
        String rule = ServiceSupport.name(getRuleName().get(), EventBridgeOperations.RULE, "ruleName");
        String pattern = JsonFixtures.object(getEventPattern().get(), "eventPattern");
        if (pattern.length() > 4096) throw new GradleException("eventPattern exceeds 4096 characters.");
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).eventBridge()) {
            new EventBridgeOperations().putRule(client, bus, rule, pattern, getOverwrite().get());
        }
    }
}
