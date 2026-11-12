package io.github.gregwhitaker.awsemulator.aws.cognito;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Selects a Cognito User Pool by exact name; its ID is resolved at execution time. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class AbstractCognitoUserPoolTask extends AbstractAwsResourceTask {
    /** Exact pool name. Multiple pools with this name cause a failure. */
    @Input public abstract Property<String> getUserPoolName();

    @Option(option = "userPoolName", description = "Exact Cognito User Pool name")
    public void setUserPoolNameOption(String value) { getUserPoolName().set(value); }

    protected final String userPoolName() { return CognitoOperations.name(getUserPoolName().get(), "userPoolName"); }
}
