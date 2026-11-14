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

package io.github.gregwhitaker.awsemulator.aws.s3;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.services.s3.model.NotificationConfiguration;

/** Replaces all bucket notifications; an explicitly empty configuration removes all destinations. */
@DisableCachingByDefault(because = "Configures live emulator resources")
@AwsEmulatorSetupTask
public abstract class PutS3BucketNotificationConfigurationTask extends AbstractS3BucketTask {
    /**
     * Complete desired SDK configuration for SQS, SNS, Lambda, and/or EventBridge destinations.
     * Required, with no default. Destinations and their permissions must be provisioned separately.
     */
    @Input public abstract Property<NotificationConfiguration> getNotificationConfiguration();

    @TaskAction
    public void putNotifications() {
        String bucket = bucketName();
        var configuration = getNotificationConfiguration().get();
        S3BucketConfiguration.notifications(configuration);
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).s3()) {
            client.putBucketNotificationConfiguration(request -> request.bucket(bucket).notificationConfiguration(configuration));
        }
        getLogger().lifecycle("Replaced S3 bucket notification configuration: {}", bucket);
    }
}
