/*
 * Copyright 2026 Greg Whitaker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
        String runtimeName = extension.getRuntimeName().get();
        List<Task> setup = new ArrayList<>();
        for (AbstractAwsResourceTask task : project.getTasks().withType(AbstractAwsResourceTask.class).matching(candidate ->
                candidate.getClass().isAnnotationPresent(AwsEmulatorSetupTask.class)
                    && !candidate.getExtensions().getExtraProperties().has("awsEmulatorResourceSet"))) {
            if (excluded.contains(task.getName()) || excluded.contains(task.getPath())) continue;
            // Unassigned tasks follow Boot; tasks explicitly attached to another named runtime are left alone.
            if (!task.getRuntimeName().get().equals(runtimeName)) {
                try { task.getRuntimeName().convention(runtimeName); }
                catch (IllegalStateException ignored) { continue; }
                if (!task.getRuntimeName().get().equals(runtimeName)) continue;
            }
            setup.add(task);
        }
        Set<Task> visited = new HashSet<>();
        EmulatorMode mode = extension.getMode().get();
        for (Task task : setup) alignRuntime(task, project, runtimeName, mode, consumer, visited);
        return setup;
    }

    private static void alignRuntime(Task task, Project project, String runtimeName, EmulatorMode mode, String consumer, Set<Task> visited) {
        if (!visited.add(task)) return;
        if (task.getProject() == project && (task.getName().equals("bootRunLocal") || task.getName().equals("bootRunLocalDebug"))) {
            throw new GradleException("Spring Boot setup tasks cannot depend on " + task.getPath() + ". The application runs after setup.");
        }
        if (task instanceof AbstractAwsResourceTask resource) {
            if (task.getProject() != project) {
                throw new GradleException("Spring Boot setup resources must belong to the application's project: " + task.getPath());
            }
            if (!resource.getRuntimeName().get().equals(runtimeName)) {
                try { resource.getRuntimeName().convention(runtimeName); }
                catch (IllegalStateException e) { throw incompatibleRuntime(resource, runtimeName, consumer); }
                if (!resource.getRuntimeName().get().equals(runtimeName)) throw incompatibleRuntime(resource, runtimeName, consumer);
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
            alignRuntime(dependency, project, runtimeName, mode, consumer, visited);
        }
    }

    private static GradleException incompatibleRuntime(AbstractAwsResourceTask resource, String name, String consumer) {
        return new GradleException("Spring Boot setup task " + resource.getPath() + " uses runtime '"
            + resource.getRuntimeName().get() + "' but " + consumer + " uses '" + name
            + "'. Use separate tasks or a reusable resource set for different runtimes.");
    }

    private static GradleException incompatibleMode(AbstractAwsResourceTask resource, EmulatorMode mode, String consumer) {
        return new GradleException("Spring Boot setup task " + resource.getPath() + " uses " + resource.getMode().get()
            + " but " + consumer + " uses " + mode + ". Set the resource mode to awsEmulator.springBoot.mode, "
            + "exclude it with awsEmulator.springBoot.excludedSetupTasks, or disable autoCreateResources.");
    }
}
