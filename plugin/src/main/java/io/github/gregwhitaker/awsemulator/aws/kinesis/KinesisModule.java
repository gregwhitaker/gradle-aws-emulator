package io.github.gregwhitaker.awsemulator.aws.kinesis;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Project;

/** Registers Kinesis tasks without starting the emulator. */
public final class KinesisModule {
    private KinesisModule() {}
    public static void load(Project project) {
        configure(project, CreateKinesisStreamTask.class);
        configure(project, DeleteKinesisStreamTask.class);
        configure(project, ListKinesisStreamsTask.class);
        configure(project, PublishKinesisRecordsTask.class);
        project.getTasks().register("listKinesisStreams", ListKinesisStreamsTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("deleteKinesisStream", DeleteKinesisStreamTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
    }
    private static <T extends AbstractAwsResourceTask> void configure(Project project, Class<T> type) {
        project.getTasks().withType(type).configureEach(t -> {
            t.setGroup("AWS Emulator - Kinesis");
            t.setDescription("Runs " + type.getSimpleName().replace("Task", "") + " against AWS Emulator.");
        });
    }
}
