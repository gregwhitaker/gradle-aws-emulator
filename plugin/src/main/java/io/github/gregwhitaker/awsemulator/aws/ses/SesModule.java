package io.github.gregwhitaker.awsemulator.aws.ses;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Project;

/** Registers SES API v2 tasks without connecting to Docker or AWS. */
public final class SesModule {
    private SesModule() {}

    public static void load(Project project) {
        configure(project, CreateSesEmailIdentityTask.class, "Creates or reuses an SES identity and waits for local verification.");
        configure(project, DeleteSesEmailIdentityTask.class, "Deletes an SES email or domain identity.");
        configure(project, ListSesEmailIdentitiesTask.class, "Lists SES identities and verification status.");
        configure(project, PutSesEmailTemplateTask.class, "Creates, reuses, or explicitly replaces an SES email template.");
        configure(project, DeleteSesEmailTemplateTask.class, "Deletes an SES email template.");
        configure(project, CreateSesConfigurationSetTask.class, "Creates or reuses an SES configuration set.");
        configure(project, DeleteSesConfigurationSetTask.class, "Deletes an SES configuration set.");
        configure(project, ListSesConfigurationSetsTask.class, "Lists SES configuration sets.");
        configure(project, PutSesSnsEventDestinationTask.class, "Configures an SES event destination using an existing SNS topic ARN.");
        configure(project, SendSesEmailTask.class, "Submits a simple or raw email to the local SES API.");
        configure(project, GetSesMessageInsightsTask.class, "Exports SES message metadata and events without email bodies.");
        development(project, "listSesEmailIdentities", ListSesEmailIdentitiesTask.class);
        development(project, "deleteSesEmailIdentity", DeleteSesEmailIdentityTask.class);
        development(project, "deleteSesEmailTemplate", DeleteSesEmailTemplateTask.class);
        development(project, "listSesConfigurationSets", ListSesConfigurationSetsTask.class);
        development(project, "deleteSesConfigurationSet", DeleteSesConfigurationSetTask.class);
        development(project, "sendSesEmail", SendSesEmailTask.class);
        development(project, "getSesMessageInsights", GetSesMessageInsightsTask.class);
        project.getTasks().withType(SendSesEmailTask.class).configureEach(task -> task.getMessageIdFile()
            .convention(project.getLayout().getBuildDirectory().file("aws-emulator/ses/" + task.getName() + "/message-id.txt")));
        project.getTasks().withType(GetSesMessageInsightsTask.class).configureEach(task -> task.getOutputFile()
            .convention(project.getLayout().getBuildDirectory().file("aws-emulator/ses/" + task.getName() + "/insights.json")));
    }

    private static <T extends AbstractAwsResourceTask> void configure(Project project, Class<T> type, String description) {
        project.getTasks().withType(type).configureEach(task -> {
            task.setGroup("AWS Emulator - SES");
            task.setDescription(description);
        });
    }

    private static <T extends AbstractAwsResourceTask> void development(Project project, String name, Class<T> type) {
        project.getTasks().register(name, type, task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
    }
}
