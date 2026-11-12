package io.github.gregwhitaker.awsemulator.aws.eventbridge;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Project;

/** Registers EventBridge tasks without starting the emulator. */
public final class EventBridgeModule {
    private EventBridgeModule() {}
    public static void load(Project project) {
        configure(project, CreateEventBusTask.class);
        configure(project, PutEventBridgeRuleTask.class);
        configure(project, PutEventBridgeTargetsTask.class);
        configure(project, PutEventBridgeSqsTargetTask.class);
        configure(project, PublishEventBridgeEventsTask.class);
        configure(project, ListEventBusesTask.class);
        configure(project, ListEventBridgeRulesTask.class);
        configure(project, DeleteEventBridgeRuleTask.class);
        configure(project, DeleteEventBusTask.class);
        project.getTasks().register("listEventBuses", ListEventBusesTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("listEventBridgeRules", ListEventBridgeRulesTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("deleteEventBridgeRule", DeleteEventBridgeRuleTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
        project.getTasks().register("deleteEventBus", DeleteEventBusTask.class, t -> t.getMode().convention(EmulatorMode.DEVELOPMENT));
    }
    private static <T extends AbstractAwsResourceTask> void configure(Project project, Class<T> type) {
        project.getTasks().withType(type).configureEach(t -> {
            t.setGroup("AWS Emulator - EventBridge");
            t.setDescription("Runs " + type.getSimpleName().replace("Task", "") + " against AWS Emulator.");
        });
    }
}
