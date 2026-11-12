package io.github.gregwhitaker.awsemulator.boot.internal;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.boot.SpringBootEmulatorExtension;
import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Resolves setup dependencies while building the task graph, before configuration-cache storage. */
final class SpringBootResourceSetup {
    private SpringBootResourceSetup() {}

    static List<Task> discover(Project project, SpringBootEmulatorExtension extension, String consumer) {
        if (!extension.getAutoCreateResources().get()) return List.of();
        Set<String> excluded = new HashSet<>(extension.getExcludedSetupTasks().get());
        List<Task> setup = new ArrayList<>();
        for (Task task : project.getTasks().withType(AbstractAwsResourceTask.class).matching(candidate ->
                candidate.getClass().isAnnotationPresent(AwsEmulatorSetupTask.class))) {
            if (!excluded.contains(task.getName()) && !excluded.contains(task.getPath())) setup.add(task);
        }
        Set<Task> visited = new HashSet<>();
        EmulatorMode mode = extension.getMode().get();
        for (Task task : setup) alignRuntime(task, project, mode, consumer, visited);
        return setup;
    }

    private static void alignRuntime(Task task, Project project, EmulatorMode mode, String consumer, Set<Task> visited) {
        if (!visited.add(task)) return;
        if (task.getProject() == project && (task.getName().equals("bootRunLocal") || task.getName().equals("bootRunLocalDebug"))) {
            throw new GradleException("Spring Boot setup tasks cannot depend on " + task.getPath() + ". The application runs after setup.");
        }
        if (task instanceof AbstractAwsResourceTask resource) {
            if (task.getProject() != project) {
                throw new GradleException("Spring Boot setup resources must belong to the application's project: " + task.getPath());
            }
            if (resource.getMode().get() != mode) {
                // Change the default for this Boot invocation, while respecting an explicitly selected mode.
                try { resource.getMode().convention(mode); }
                catch (IllegalStateException error) { throw incompatibleMode(resource, mode, consumer); }
                if (resource.getMode().get() != mode) throw incompatibleMode(resource, mode, consumer);
            }
        }
        // Explicit prerequisites (including fixture tasks) must use the same instance as their resource task.
        for (Task dependency : task.getTaskDependencies().getDependencies(task)) {
            alignRuntime(dependency, project, mode, consumer, visited);
        }
    }

    private static GradleException incompatibleMode(AbstractAwsResourceTask resource, EmulatorMode mode, String consumer) {
        return new GradleException("Spring Boot setup task " + resource.getPath() + " uses " + resource.getMode().get()
            + " but " + consumer + " uses " + mode + ". Set the resource mode to springBootEmulator.mode, "
            + "exclude it with springBootEmulator.excludedSetupTasks, or disable autoCreateResources.");
    }
}
