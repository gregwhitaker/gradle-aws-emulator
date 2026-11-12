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
import org.gradle.api.Project;

/** Loads the AWS service modules and their task configuration. */
public final class AwsModule {
    private AwsModule() {}

    public static void load(Project project) {
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
