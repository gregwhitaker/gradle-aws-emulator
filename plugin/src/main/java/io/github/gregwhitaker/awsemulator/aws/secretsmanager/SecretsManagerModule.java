package io.github.gregwhitaker.awsemulator.aws.secretsmanager;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Project;

/** Registers Secrets Manager tasks without starting the emulator. */
public final class SecretsManagerModule {
    private SecretsManagerModule() {}
    public static void load(Project project) {
        configure(project, PutSecretTask.class);
        configure(project, PutSecretsTask.class);
        configure(project, ListSecretsTask.class);
        configure(project, DeleteSecretsTask.class);
        configure(project, DescribeSecretTask.class);
        project.getTasks().register("listSecrets", ListSecretsTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("deleteSecrets", DeleteSecretsTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("describeSecret", DescribeSecretTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
    }
    private static <T extends AbstractAwsResourceTask> void configure(Project project, Class<T> type) {
        project.getTasks().withType(type).configureEach(t -> {
            t.setGroup("AWS Emulator - Secrets Manager");
            t.setDescription("Runs " + type.getSimpleName().replace("Task", "") + " against AWS Emulator.");
        });
    }
}
