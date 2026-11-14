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
import io.github.gregwhitaker.awsemulator.internal.LocalTaskDependencies;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import io.github.gregwhitaker.awsemulator.model.ScenarioResetScope;
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
    private final Map<String, TaskProvider<Task>> cleanTasks = new LinkedHashMap<>();
    private final Map<String, List<TaskProvider<? extends AbstractAwsResourceTask>>> cleanup = new HashMap<>();
    private final Set<String> resets = new HashSet<>();
    private final Set<String> prepared = new HashSet<>();
    private final Set<String> suffixes = new HashSet<>();
    private final Map<String, String> activeScenarios = new HashMap<>();

    private ScenarioModule(Project project, AwsEmulatorExtension extension) {
        this.project = project;
        this.extension = extension;
    }

    static void load(Project project, AwsEmulatorExtension extension) {
        new ScenarioModule(project, extension).register();
    }

    private void register() {
        var restart = project.getTasks().withType(RestartAwsEmulatorTask.class);
        project.getTasks().withType(StartAwsEmulatorTask.class).configureEach(task -> task.mustRunAfter(restart));
        project.getTasks().withType(AbstractAwsResourceTask.class).configureEach(task -> task.mustRunAfter(restart));
        extension.getScenarios().all(this::registerScenario);
        var selected = project.getProviders().gradleProperty("awsEmulator.scenario").orElse(extension.getDefaultScenario());
        command("seedAwsEmulator", "Creates resources and loads the selected development scenario.")
            .configure(task -> task.dependsOn(project.provider(() -> selected(seedTasks, selected.get()))));
        command("resetAwsEmulator", "Resets the selected scenario using its configured reset scope, then seeds it.")
            .configure(task -> task.dependsOn(project.provider(() -> selected(resetTasks, selected.get()))));
        command("cleanAwsEmulator", "Runs only the selected scenario's explicit cleanup, preserving its runtime.")
            .configure(task -> task.dependsOn(project.provider(() -> selected(cleanTasks, selected.get()))));
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
        var reset = command("resetAwsEmulator" + suffix, "Resets and seeds the '" + name + "' scenario using its configured reset scope.");
        reset.configure(task -> task.dependsOn(project.provider(() -> prepareReset(scenario, seed))));
        resetTasks.put(name, reset);
        var clean = command("cleanAwsEmulator" + suffix, "Runs explicit cleanup for the '" + name + "' scenario.");
        clean.configure(task -> task.dependsOn(project.provider(() -> prepareCleanup(scenario))));
        cleanTasks.put(name, clean);
    }

    private List<Object> prepareReset(EmulatorScenario scenario, TaskProvider<Task> seed) {
        String selected = project.getProviders().gradleProperty("awsEmulator.resetScope")
            .getOrElse(scenario.getResetScope().get().name());
        ScenarioResetScope scope;
        try { scope = ScenarioResetScope.valueOf(selected.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new GradleException("awsEmulator.resetScope must be RUNTIME or RESOURCES."); }
        if (scope == ScenarioResetScope.RUNTIME) {
            return List.of(extension.lifecycleTask("restartAwsEmulator", scenario.getRuntimeName().get()), seed);
        }
        var cleaning = prepareCleanup(scenario);
        var setup = prepare(scenario);
        if (resets.add(scenario.getName())) {
            Set<Task> setupTasks = new LinkedHashSet<>();
            Set<Task> cleanupTasks = new LinkedHashSet<>();
            collectResources(setup, setupTasks);
            collectResources(cleaning, cleanupTasks);
            if (setupTasks.stream().anyMatch(cleanupTasks::contains)) {
                throw new GradleException("Scenario cleanup must use separate tasks and must not depend on provisioning or seeding tasks.");
            }
            for (Task task : setupTasks) task.dependsOn(cleaning);
        }
        return List.of(seed, cleaning);
    }

    private List<TaskProvider<? extends AbstractAwsResourceTask>> prepareCleanup(EmulatorScenario scenario) {
        activate(scenario);
        if (cleanup.containsKey(scenario.getName())) return cleanup.get(scenario.getName());
        List<TaskProvider<? extends AbstractAwsResourceTask>> tasks = new ArrayList<>(scenario.cleaning());
        tasks.addAll(extension.resolveCleanup(scenario));
        if (tasks.isEmpty()) throw new GradleException("Scenario '" + scenario.getName()
            + "' has no cleanup steps. Declare cleanup on the scenario or its resource sets before using resource-scoped reset or clean.");
        String runtimeName = scenario.getRuntimeName().get();
        TaskProvider<Task> start = extension.usesExternalRuntime(runtimeName) ? null : extension.lifecycleTask("startAwsEmulator", runtimeName);
        Set<Task> visited = new HashSet<>();
        Set<Task> direct = new HashSet<>();
        TaskProvider<? extends AbstractAwsResourceTask> previous = null;
        for (var provider : tasks) {
            var task = provider.get();
            if (!direct.add(task)) throw new GradleException("A task may appear only once in scenario cleanup: " + task.getPath());
            configureResources(task, runtimeName, start, visited);
            if (previous != null) task.dependsOn(previous);
            previous = provider;
        }
        var result = List.copyOf(tasks);
        cleanup.put(scenario.getName(), result);
        return result;
    }

    private void collectResources(Object dependencies, Set<Task> resources) {
        Set<Task> visited = new HashSet<>();
        LocalTaskDependencies.visit(project, dependencies, task -> collectResource(task, visited, resources));
    }

    private void collectResource(Task task, Set<Task> visited, Set<Task> resources) {
        if (!visited.add(task)) return;
        if (task instanceof AbstractAwsResourceTask) resources.add(task);
        LocalTaskDependencies.visitPrerequisites(project, task, dependency -> collectResource(dependency, visited, resources));
    }

    private void activate(EmulatorScenario scenario) {
        String active = activeScenarios.putIfAbsent(scenario.getRuntimeName().get(), scenario.getName());
        if (active != null && !active.equals(scenario.getName())) {
            throw new GradleException("Select only one AWS Emulator scenario per runtime per Gradle invocation: " + scenario.getRuntimeName().get());
        }
    }

    private TaskProvider<Task> selected(Map<String, TaskProvider<Task>> tasks, String name) {
        var task = tasks.get(name);
        if (task == null) throw new GradleException("Unknown AWS Emulator scenario '" + name + "'. Available scenarios: "
            + String.join(", ", seedTasks.keySet()) + ". Set awsEmulator.defaultScenario or -PawsEmulator.scenario=<name>.");
        return task;
    }

    private List<Object> prepare(EmulatorScenario scenario) {
        List<Object> dependencies = new ArrayList<>();
        String runtimeName = scenario.getRuntimeName().get();
        TaskProvider<Task> start = extension.usesExternalRuntime(runtimeName) ? null : extension.lifecycleTask("startAwsEmulator", runtimeName);
        if (start != null) dependencies.add(start);
        Object resources = extension.resolveSetup(scenario.resources(), runtimeName, EmulatorMode.DEVELOPMENT);
        dependencies.add(resources);
        dependencies.addAll(scenario.provisioning());
        dependencies.addAll(scenario.seeding());
        activate(scenario);
        if (!prepared.add(scenario.getName())) {
            return dependencies;
        }
        Set<Task> direct = new HashSet<>();
        Set<Task> visited = new HashSet<>();
        for (var provider : concat(scenario)) {
            Task task = provider.get();
            if (!direct.add(task)) throw new GradleException("A task may appear only once in a scenario: " + task.getPath());
            configureResources(task, runtimeName, start, visited);
            task.dependsOn(resources);
        }
        configureDefinitions(resources, runtimeName, start, visited);
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

    private void configureDefinitions(Object definitions, String runtimeName, TaskProvider<Task> start, Set<Task> visited) {
        if (definitions instanceof Iterable<?> values) {
            for (Object value : values) configureDefinitions(value, runtimeName, start, visited);
        } else if (definitions instanceof Provider<?> provider) {
            configureDefinitions(provider.get(), runtimeName, start, visited);
        } else if (definitions instanceof Task task) {
            configureResources(task, runtimeName, start, visited);
        }
    }

    private void configureResources(Task task, String runtimeName, TaskProvider<Task> start, Set<Task> visited) {
        if (!visited.add(task)) return;
        if (task instanceof AbstractAwsResourceTask resource) {
            if (task.getProject() != project) throw new GradleException("Scenario resource tasks must belong to the same project: " + task.getPath());
            ResourceSetModule.selectRuntime(resource, runtimeName);
            if (resource.getMode().get() != EmulatorMode.DEVELOPMENT) resource.getMode().set(EmulatorMode.DEVELOPMENT);
            resource.getMode().disallowChanges();
            if (start != null) resource.dependsOn(start);
        }
        // Select the runtime before resolving prerequisites, including their lazy service bindings.
        LocalTaskDependencies.visitPrerequisites(project, task, dependency -> configureResources(dependency, runtimeName, start, visited));
    }

    private TaskProvider<Task> command(String name, String description) {
        return project.getTasks().register(name, task -> {
            task.setGroup("AWS Emulator - Scenarios");
            task.setDescription(description);
        });
    }
}
