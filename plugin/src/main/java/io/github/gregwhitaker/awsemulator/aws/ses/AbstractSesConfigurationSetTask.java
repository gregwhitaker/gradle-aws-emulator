package io.github.gregwhitaker.awsemulator.aws.ses;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Selects an SES configuration set in the task's runtime. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class AbstractSesConfigurationSetTask extends AbstractAwsResourceTask {
    /** Exact SES configuration set name. */
    @Input public abstract Property<String> getConfigurationSetName();

    @Option(option = "configurationSetName", description = "Exact SES configuration set name")
    public void setConfigurationSetNameOption(String value) { getConfigurationSetName().set(value); }

    protected final String configurationSetName() { return SesOperations.name(getConfigurationSetName().get(), "configurationSetName"); }
}
