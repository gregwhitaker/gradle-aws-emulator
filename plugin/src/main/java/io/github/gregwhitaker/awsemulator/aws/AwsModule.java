package io.github.gregwhitaker.awsemulator.aws;

import io.github.gregwhitaker.awsemulator.aws.s3.S3Module;
import io.github.gregwhitaker.awsemulator.aws.sqs.SqsModule;
import org.gradle.api.Project;

/** Loads the AWS service modules and their task configuration. */
public final class AwsModule {
    private AwsModule() {}

    public static void load(Project project) {
        S3Module.load(project);
        SqsModule.load(project);
    }
}
