package io.github.gregwhitaker.awsemulator.aws.ses;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Selects an SES identity in the task's runtime. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class AbstractSesEmailIdentityTask extends AbstractAwsResourceTask {
    /** Bare sender email address or domain name. */
    @Input public abstract Property<String> getEmailIdentity();

    @Option(option = "emailIdentity", description = "Bare sender email address or domain name")
    public void setEmailIdentityOption(String value) { getEmailIdentity().set(value); }

    protected final String emailIdentity() { return SesOperations.identity(getEmailIdentity().get()); }
}
