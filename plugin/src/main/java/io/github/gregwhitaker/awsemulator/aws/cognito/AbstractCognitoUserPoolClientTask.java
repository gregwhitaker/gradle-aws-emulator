package io.github.gregwhitaker.awsemulator.aws.cognito;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Selects an app client by exact name within the selected Cognito User Pool. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class AbstractCognitoUserPoolClientTask extends AbstractCognitoUserPoolTask {
    /** Exact app client name. Multiple matching clients cause a failure. */
    @Input public abstract Property<String> getClientName();

    @Option(option = "clientName", description = "Exact Cognito app client name")
    public void setClientNameOption(String value) { getClientName().set(value); }

    protected final String clientName() { return CognitoOperations.name(getClientName().get(), "clientName"); }
}
