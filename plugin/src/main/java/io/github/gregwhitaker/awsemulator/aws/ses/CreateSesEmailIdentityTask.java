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

package io.github.gregwhitaker.awsemulator.aws.ses;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.core.exception.SdkException;
import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import io.github.gregwhitaker.awsemulator.aws.internal.ServiceSupport;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;

/** Creates or reuses an identity, then waits for the emulator to verify it. */
@AwsEmulatorSetupTask
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class CreateSesEmailIdentityTask extends AbstractSesEmailIdentityTask {
    public CreateSesEmailIdentityTask() { getWaitTimeoutSeconds().convention(30); }

    /** Maximum time to wait for local identity verification; defaults to 30 seconds. */
    @Input public abstract Property<Integer> getWaitTimeoutSeconds();
    @TaskAction public void executeTask() {
        String identity = emailIdentity();
        int timeout = getWaitTimeoutSeconds().get();
        ServiceSupport.positive(timeout);
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sesV2()) {
            new SesOperations().createIdentity(client, identity, timeout);
            getLogger().lifecycle("SES identity ready: {}", identity);
        } catch (SdkException error) { throw SesOperations.failure("CreateSesEmailIdentity", error); }
    }
}
