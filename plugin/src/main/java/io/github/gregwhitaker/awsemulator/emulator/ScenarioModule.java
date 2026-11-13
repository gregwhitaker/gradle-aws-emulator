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
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import java.util.*;

/** Builds ordinary Gradle dependency graphs; no task actions are invoked manually. */
final class ScenarioModule {
    private final Project project;
    private final AwsEmulatorExtension extension;
    private final Map<String, TaskProvider<Task>> seedTasks = new LinkedHashMap<>();
    private final Map<String, TaskProvider<Task>> resetTasks = new LinkedHashMap<>();
    private final Set<String> suffixes = new HashSet<>();
    private String activeScenario;

    private ScenarioModule(Project project, AwsEmulatorExtension extension) {
        this.project = project;
        this.extension = extension;
    }

    static void load(Project project, AwsEmulatorExtension extension) {
        new ScenarioModule(project, extension).register();
    }

    private void register() {
        var restart = project.getTasks().named("restartAwsEmulator");
        project.getTasks().named("startAwsEmulator").configure(task -> task.mustRunAfter(restart));
        project.getTasks().withType(AbstractAwsResourceTask.class).configureEach(task -> task.mustRunAfter(restart));
        extension.getScenarios().all(this::registerScenario);
        var selected = project.getProviders().gradleProperty("awsEmulator.scenario").orElse(extension.getDefaultScenario());
        command("seedAwsEmulator", "Creates resources and loads the selected development scenario.")
            .configure(task -> task.dependsOn(project.provider(() -> selected(seedTasks, selected.get()))));
        command("resetAwsEmulator", "Restarts the development emulator and loads the selected scenario.")
            .configure(task -> task.dependsOn(project.provider(() -> selected(resetTasks, selected.get()))));
    }

    private void registerScenario(EmulatorScenario scenario) {
        String name = scenario.getName();
        if (!name.matches("[a-z][a-zA-Z0-9]*(?:-[a-zA-Z0-9]+)*")) {
            throw new GradleException("Scenario names must start with a lowercase letter and contain letters, numbers, or single hyphens.");
        }
        StringBuilder suffix = new StringBuilder();
        for (String part : name.split("-")) suffix.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        if (!suffixes.add(suffix.toString())) throw new GradleException("Scenario names produce duplicate task names: " + name);
        var seed = command("seedAwsEmulator" + suffix, "Creates resources and loads the '" + name + "' development scenario.");
        seed.configure(task -> task.dependsOn(project.provider(() -> prepare(scenario))));
        seedTasks.put(name, seed);
        var reset = command("resetAwsEmulator" + suffix, "Restarts the development emulator and loads the '" + name + "' scenario.");
        reset.configure(task -> task.dependsOn(project.getTasks().named("restartAwsEmulator"), seed));
        resetTasks.put(name, reset);
    }

    private TaskProvider<Task> selected(Map<String, TaskProvider<Task>> tasks, String name) {
        var task = tasks.get(name);
        if (task == null) throw new GradleException("Unknown AWS Emulator scenario '" + name + "'. Available scenarios: "
            + String.join(", ", seedTasks.keySet()) + ". Set awsEmulator.defaultScenario or -PawsEmulator.scenario=<name>.");
        return task;
    }

    private List<Object> prepare(EmulatorScenario scenario) {
        List<Object> dependencies = new ArrayList<>();
        TaskProvider<Task> start = extension.usesExternalRuntime() ? null : project.getTasks().named("startAwsEmulator");
        if (start != null) dependencies.add(start);
        Object resources = extension.resolveSetup(scenario.resources(), EmulatorMode.DEVELOPMENT);
        dependencies.add(resources);
        dependencies.addAll(scenario.provisioning());
        dependencies.addAll(scenario.seeding());
        if (activeScenario != null) {
            if (!activeScenario.equals(scenario.getName())) throw new GradleException("Select only one AWS Emulator scenario per Gradle invocation.");
            return dependencies;
        }
        activeScenario = scenario.getName();
        Set<Task> direct = new HashSet<>();
        Set<Task> visited = new HashSet<>();
        for (var provider : concat(scenario)) {
            Task task = provider.get();
            if (!direct.add(task)) throw new GradleException("A task may appear only once in a scenario: " + task.getPath());
            configureResources(task, start, visited);
            task.dependsOn(resources);
        }
        configureDefinitions(resources, start, visited);
        TaskProvider<? extends AbstractAwsResourceTask> previous = null;
        for (var provider : scenario.seeding()) {
            var task = provider.get();
            task.dependsOn(scenario.provisioning());
            if (previous != null) task.dependsOn(previous);
            previous = provider;
        }
        return dependencies;
    }

    private List<TaskProvider<? extends AbstractAwsResourceTask>> concat(EmulatorScenario scenario) {
        var tasks = new ArrayList<>(scenario.provisioning());
        tasks.addAll(scenario.seeding());
        return tasks;
    }

    private void configureDefinitions(Object definitions, TaskProvider<Task> start, Set<Task> visited) {
        if (definitions instanceof Iterable<?> values) {
            for (Object value : values) configureDefinitions(value, start, visited);
        } else if (definitions instanceof Provider<?> provider) {
            configureDefinitions(provider.get(), start, visited);
        } else if (definitions instanceof Task task) {
            configureResources(task, start, visited);
        }
    }

    private void configureResources(Task task, TaskProvider<Task> start, Set<Task> visited) {
        if (!visited.add(task)) return;
        // Include resource prerequisites so a pre-existing dependsOn chain uses the same runtime.
        for (Task dependency : task.getTaskDependencies().getDependencies(task)) configureResources(dependency, start, visited);
        if (task instanceof AbstractAwsResourceTask resource) {
            if (task.getProject() != project) throw new GradleException("Scenario resource tasks must belong to the same project: " + task.getPath());
            if (resource.getMode().get() != EmulatorMode.DEVELOPMENT) resource.getMode().set(EmulatorMode.DEVELOPMENT);
            resource.getMode().disallowChanges();
            if (start != null) resource.dependsOn(start);
        }
    }

    private TaskProvider<Task> command(String name, String description) {
        return project.getTasks().register(name, task -> {
            task.setGroup("AWS Emulator - Scenarios");
            task.setDescription(description);
        });
    }
}
