package io.github.gregwhitaker.awsemulator.aws.ses;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Selects an SES template in the task's runtime. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class AbstractSesEmailTemplateTask extends AbstractAwsResourceTask {
    /** Exact SES template name. */
    @Input public abstract Property<String> getTemplateName();

    @Option(option = "templateName", description = "Exact SES template name")
    public void setTemplateNameOption(String value) { getTemplateName().set(value); }

    protected final String templateName() { return SesOperations.name(getTemplateName().get(), "templateName"); }
}
