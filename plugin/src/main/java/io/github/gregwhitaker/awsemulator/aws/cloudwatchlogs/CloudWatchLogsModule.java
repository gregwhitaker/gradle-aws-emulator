package io.github.gregwhitaker.awsemulator.aws.cloudwatchlogs;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Project;

/** Registers CloudWatch Logs tasks without starting the emulator. */
public final class CloudWatchLogsModule {
    private CloudWatchLogsModule() {}
    public static void load(Project project) {
        configure(project, CreateLogGroupTask.class);
        configure(project, CreateLogStreamTask.class);
        configure(project, ListLogGroupsTask.class);
        configure(project, DeleteLogGroupTask.class);
        configure(project, DeleteLogStreamTask.class);
        configure(project, GetLogEventsTask.class);
        configure(project, FilterLogEventsTask.class);
        project.getTasks().register("listLogGroups", ListLogGroupsTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("deleteLogGroup", DeleteLogGroupTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("deleteLogStream", DeleteLogStreamTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
    }
    private static <T extends AbstractAwsResourceTask> void configure(Project project, Class<T> type) {
        project.getTasks().withType(type).configureEach(t -> {
            t.setGroup("AWS Emulator - CloudWatch Logs");
            t.setDescription("Runs " + type.getSimpleName().replace("Task", "") + " against AWS Emulator.");
        });
    }
}
