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
import org.gradle.api.provider.Provider;
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

    Object resolve(Object setup, EmulatorMode mode) {
        // Mapping preserves producer dependencies on task-output providers and keeps absent providers lazy.
        if (setup instanceof Provider<?> provider) return provider.map(value -> resolve(value, mode));
        if (setup instanceof EmulatorResourceSet resources) return tasks(resources, mode);
        if (setup instanceof Buildable) return setup;
        if (setup instanceof Object[] values) return resolve(List.of(values), mode);
        if (setup instanceof Iterable<?> values) {
            List<Object> result = new ArrayList<>();
            for (Object value : values) result.add(resolve(value, mode));
            return result;
        }
        return setup;
    }

    private List<TaskProvider<? extends AbstractAwsResourceTask>> tasks(EmulatorResourceSet resources, EmulatorMode mode) {
        if (extension.getResourceSets().findByName(resources.getName()) != resources) {
            throw new GradleException("AWS Emulator resource sets must belong to the consumer's project: " + resources.getName());
        }
        String key = resources.getName() + "_" + mode;
        if (resolving.contains(key)) throw new GradleException("Cyclic AWS Emulator resource set includes: " + resources.getName());
        if (instances.containsKey(key)) return instances.get(key);
        resolving.add(key);
        try {
            List<TaskProvider<? extends AbstractAwsResourceTask>> prerequisites = new ArrayList<>();
            for (var included : resources.includes()) prerequisites.addAll(tasks(included.get(), mode));
            List<TaskProvider<? extends AbstractAwsResourceTask>> result = new ArrayList<>(prerequisites);
            for (var step : resources.steps()) {
                var task = register(key, step, mode, List.copyOf(prerequisites));
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
            EmulatorMode mode, List<TaskProvider<? extends AbstractAwsResourceTask>> prerequisites) {
        return project.getTasks().register("awsEmulatorResources_" + key + "_" + step.name(), step.type(), task -> {
            task.setGroup("AWS Emulator - Resources");
            task.setDescription("Applies resource set step '" + step.name() + "' to " + mode + ".");
            task.getExtensions().getExtraProperties().set("awsEmulatorResourceSet", true);
            task.getMode().set(mode);
            task.getMode().disallowChanges();
            step.action().execute(task);
            task.dependsOn(prerequisites);
        });
    }
}
