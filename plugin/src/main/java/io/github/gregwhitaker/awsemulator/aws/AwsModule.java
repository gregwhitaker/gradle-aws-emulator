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

package io.github.gregwhitaker.awsemulator.aws;

import io.github.gregwhitaker.awsemulator.aws.s3.S3Module;
import io.github.gregwhitaker.awsemulator.aws.sqs.SqsModule;
import io.github.gregwhitaker.awsemulator.aws.sns.SnsModule;
import io.github.gregwhitaker.awsemulator.aws.dynamodb.DynamoDbModule;
import io.github.gregwhitaker.awsemulator.aws.ssm.SsmModule;
import io.github.gregwhitaker.awsemulator.aws.secretsmanager.SecretsManagerModule;
import io.github.gregwhitaker.awsemulator.aws.eventbridge.EventBridgeModule;
import io.github.gregwhitaker.awsemulator.aws.kinesis.KinesisModule;
import io.github.gregwhitaker.awsemulator.aws.kms.KmsModule;
import io.github.gregwhitaker.awsemulator.aws.cloudwatchlogs.CloudWatchLogsModule;
import io.github.gregwhitaker.awsemulator.aws.cognito.CognitoModule;
import io.github.gregwhitaker.awsemulator.aws.ses.SesModule;
import io.github.gregwhitaker.awsemulator.aws.cloudformation.CloudFormationModule;
import io.github.gregwhitaker.awsemulator.emulator.AwsEmulatorExtension;
import org.gradle.api.Project;

/** Loads the AWS service modules and their task configuration. */
public final class AwsModule {
    private AwsModule() {}

    public static void load(Project project) {
        var defaultMode = project.getExtensions().getByType(AwsEmulatorExtension.class).getDefaultMode();
        project.getTasks().withType(AbstractAwsResourceTask.class).configureEach(task -> task.getMode().convention(defaultMode));
        CloudFormationModule.load(project);
        SesModule.load(project);
        CognitoModule.load(project);
        SsmModule.load(project);
        SecretsManagerModule.load(project);
        EventBridgeModule.load(project);
        KinesisModule.load(project);
        KmsModule.load(project);
        CloudWatchLogsModule.load(project);
        S3Module.load(project);
        SqsModule.load(project);
        SnsModule.load(project);
        DynamoDbModule.load(project);
    }
}
