package io.github.gregwhitaker.awsemulator.aws.kms;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Project;

/** Registers KMS tasks without starting the emulator. */
public final class KmsModule {
    private KmsModule() {}
    public static void load(Project project) {
        configure(project, CreateKmsKeyTask.class);
        configure(project, CreateKmsAliasTask.class);
        configure(project, DeleteKmsAliasTask.class);
        configure(project, ListKmsKeysTask.class);
        configure(project, ScheduleKmsKeyDeletionTask.class);
        configure(project, EncryptKmsFileTask.class);
        configure(project, DecryptKmsFileTask.class);
        project.getTasks().register("deleteKmsAlias", DeleteKmsAliasTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("listKmsKeys", ListKmsKeysTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("scheduleKmsKeyDeletion", ScheduleKmsKeyDeletionTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
    }
    private static <T extends AbstractAwsResourceTask> void configure(Project project, Class<T> type) {
        project.getTasks().withType(type).configureEach(t -> {
            t.setGroup("AWS Emulator - KMS");
            t.setDescription("Runs " + type.getSimpleName().replace("Task", "") + " against AWS Emulator.");
        });
    }
}
