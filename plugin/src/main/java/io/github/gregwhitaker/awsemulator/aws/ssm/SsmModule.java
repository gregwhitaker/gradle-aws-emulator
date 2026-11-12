package io.github.gregwhitaker.awsemulator.aws.ssm;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Project;

/** Registers Parameter Store tasks without starting the emulator. */
public final class SsmModule {
    private SsmModule() {}
    public static void load(Project project) {
        configure(project, PutSsmParameterTask.class);
        configure(project, PutSsmParametersTask.class);
        configure(project, ListSsmParametersTask.class);
        configure(project, DeleteSsmParametersTask.class);
        project.getTasks().register("listSsmParameters", ListSsmParametersTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("deleteSsmParameters", DeleteSsmParametersTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
    }
    private static <T extends AbstractAwsResourceTask> void configure(Project project, Class<T> type) {
        project.getTasks().withType(type).configureEach(t -> {
            t.setGroup("AWS Emulator - Parameter Store");
            t.setDescription("Runs " + type.getSimpleName().replace("Task", "") + " against AWS Emulator.");
        });
    }
}
