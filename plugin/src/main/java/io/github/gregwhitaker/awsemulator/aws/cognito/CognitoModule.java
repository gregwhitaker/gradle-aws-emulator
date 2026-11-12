package io.github.gregwhitaker.awsemulator.aws.cognito;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Project;

/** Registers Cognito User Pool tasks without starting Docker. */
public final class CognitoModule {
    private CognitoModule() {}

    public static void load(Project project) {
        configure(project, CreateCognitoUserPoolTask.class, "Creates or reuses a Cognito User Pool.");
        configure(project, CreateCognitoUserPoolClientTask.class, "Creates or reuses a public Cognito app client.");
        configure(project, SeedCognitoUsersTask.class, "Seeds Cognito users and group memberships from JSON.");
        configure(project, ExportCognitoConfigurationTask.class, "Exports current Cognito IDs and local issuer/JWKS URLs.");
        configure(project, ListCognitoUserPoolsTask.class, "Lists Cognito User Pools.");
        configure(project, DeleteCognitoUserPoolTask.class, "Deletes a Cognito User Pool and its contents.");
        configure(project, DeleteCognitoUserPoolClientTask.class, "Deletes a Cognito app client.");
        project.getTasks().register("listCognitoUserPools", ListCognitoUserPoolsTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("deleteCognitoUserPool", DeleteCognitoUserPoolTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("deleteCognitoUserPoolClient", DeleteCognitoUserPoolClientTask.class,
            task -> task.getMode().convention(EmulatorMode.DEVELOPMENT));
    }

    private static <T extends AbstractAwsResourceTask> void configure(Project project, Class<T> type, String description) {
        project.getTasks().withType(type).configureEach(task -> {
            task.setGroup("AWS Emulator - Cognito");
            task.setDescription(description);
        });
    }
}
