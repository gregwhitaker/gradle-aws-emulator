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

package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.Buildable;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.api.tasks.TaskProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Materializes selected definitions while resolving dependencies, before configuration-cache storage. */
final class ResourceSetModule {
    private final Project project;
    private final AwsEmulatorExtension extension;
    private final Map<String, List<TaskProvider<? extends AbstractAwsResourceTask>>> instances = new HashMap<>();
    private final Set<String> resolving = new HashSet<>();

    ResourceSetModule(Project project, AwsEmulatorExtension extension) {
        this.project = project;
        this.extension = extension;
    }

    Object resolve(Object setup, String runtimeName, EmulatorMode mode) {
        // Gradle can use a TaskProvider's producer without evaluating map transformations.
        // Realize the selected setup task here so its runtime is aligned before graph traversal.
        if (setup instanceof TaskProvider<?> task) return resolve(task.get(), runtimeName, mode);
        // Preserve task-output producers while aligning them even when Gradle skips the mapping.
        if (setup instanceof Provider<?> provider) return alignDependencies(
            project.files(provider.map(value -> resolve(value, runtimeName, mode))).getBuildDependencies(), runtimeName);
        if (setup instanceof EmulatorResourceSet resources) return tasks(resources, runtimeName, mode);
        if (setup instanceof String path) setup = project.getTasks().getByPath(path);
        if (setup instanceof Task task) {
            alignSetup(task, runtimeName, new HashSet<>());
            return task;
        }
        if (setup instanceof Buildable buildable) return alignDependencies(buildable.getBuildDependencies(), runtimeName);
        if (setup instanceof Object[] values) return resolve(List.of(values), runtimeName, mode);
        if (setup instanceof Iterable<?> values) {
            List<Object> result = new ArrayList<>();
            for (Object value : values) result.add(resolve(value, runtimeName, mode));
            return result;
        }
        return setup;
    }

    private TaskDependency alignDependencies(TaskDependency dependencies, String runtimeName) {
        return consumer -> {
            Set<? extends Task> tasks = dependencies.getDependencies(consumer);
            Set<Task> visited = new HashSet<>();
            for (Task task : tasks) alignSetup(task, runtimeName, visited);
            return tasks;
        };
    }

    private void alignSetup(Task task, String runtimeName, Set<Task> visited) {
        if (!visited.add(task)) return;
        if (task instanceof AbstractAwsResourceTask resource) selectRuntime(resource, runtimeName);
        for (Task dependency : task.getTaskDependencies().getDependencies(task)) alignSetup(dependency, runtimeName, visited);
    }

    static void selectRuntime(AbstractAwsResourceTask resource, String runtimeName) {
        if (!resource.getRuntimeName().get().equals(runtimeName)) {
            try { resource.getRuntimeName().convention(runtimeName); }
            catch (IllegalStateException e) { throw incompatibleRuntime(resource, runtimeName); }
            if (!resource.getRuntimeName().get().equals(runtimeName)) throw incompatibleRuntime(resource, runtimeName);
        }
        resource.getRuntimeName().disallowChanges();
    }

    private static GradleException incompatibleRuntime(AbstractAwsResourceTask resource, String runtimeName) {
        return new GradleException("AWS Emulator setup task " + resource.getPath() + " uses runtime '"
            + resource.getRuntimeName().get() + "' but its consumer uses '" + runtimeName
            + "'. Use separate tasks or a reusable resource set for different runtimes.");
    }

    private List<TaskProvider<? extends AbstractAwsResourceTask>> tasks(EmulatorResourceSet resources, String runtimeName, EmulatorMode mode) {
        if (extension.getResourceSets().findByName(resources.getName()) != resources) {
            throw new GradleException("AWS Emulator resource sets must belong to the consumer's project: " + resources.getName());
        }
        String key = resources.getName() + "_" + mode;
        if (!AwsEmulatorExtension.DEFAULT_RUNTIME.equals(runtimeName)) key = runtimeName + "_" + key;
        if (resolving.contains(key)) throw new GradleException("Cyclic AWS Emulator resource set includes: " + resources.getName());
        if (instances.containsKey(key)) return instances.get(key);
        resolving.add(key);
        try {
            List<TaskProvider<? extends AbstractAwsResourceTask>> prerequisites = new ArrayList<>();
            for (var included : resources.includes()) prerequisites.addAll(tasks(included.get(), runtimeName, mode));
            List<TaskProvider<? extends AbstractAwsResourceTask>> result = new ArrayList<>(prerequisites);
            for (var step : resources.steps()) {
                var task = register(key, step, runtimeName, mode, List.copyOf(prerequisites));
                result.add(task);
                prerequisites = List.of(task);
            }
            var snapshot = List.copyOf(result);
            instances.put(key, snapshot);
            return snapshot;
        } finally {
            resolving.remove(key);
        }
    }

    private <T extends AbstractAwsResourceTask> TaskProvider<T> register(String key, EmulatorResourceSet.Step<T> step,
            String runtimeName, EmulatorMode mode, List<TaskProvider<? extends AbstractAwsResourceTask>> prerequisites) {
        return project.getTasks().register("awsEmulatorResources_" + key + "_" + step.name(), step.type(), task -> {
            task.setGroup("AWS Emulator - Resources");
            task.setDescription("Applies resource set step '" + step.name() + "' to " + mode + ".");
            task.getExtensions().getExtraProperties().set("awsEmulatorResourceSet", true);
            task.getMode().set(mode);
            task.getMode().disallowChanges();
            task.getRuntimeName().set(runtimeName);
            task.getRuntimeName().disallowChanges();
            step.action().execute(task);
            task.dependsOn(prerequisites);
        });
    }
}
