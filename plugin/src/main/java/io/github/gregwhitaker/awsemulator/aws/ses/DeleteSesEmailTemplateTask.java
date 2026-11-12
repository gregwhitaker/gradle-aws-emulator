package io.github.gregwhitaker.awsemulator.aws.ses;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.core.exception.SdkException;

/** Deletes one stored template; an absent template is already deleted. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class DeleteSesEmailTemplateTask extends AbstractSesEmailTemplateTask {
    @TaskAction public void executeTask() {
        String name = templateName();
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sesV2()) {
            new SesOperations().deleteTemplate(client, name);
        } catch (SdkException error) { throw SesOperations.failure("DeleteSesEmailTemplate", error); }
    }
}
