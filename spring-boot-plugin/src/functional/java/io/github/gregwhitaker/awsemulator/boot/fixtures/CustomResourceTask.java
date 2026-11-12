package io.github.gregwhitaker.awsemulator.boot.fixtures;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;

/** Test fixture representing an explicitly marked custom resource task. */
@AwsEmulatorSetupTask
public abstract class CustomResourceTask extends AbstractAwsResourceTask {}
