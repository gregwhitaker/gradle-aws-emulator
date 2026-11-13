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

import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import io.github.gregwhitaker.awsemulator.internal.ConfigureEmulatorAction;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.GradleException;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;

/** Configuration for the AWS Emulator runtime and its opted-in consumers. */
public abstract class AwsEmulatorExtension extends EmulatorRuntimeConfiguration {
    /** Reserved name for the runtime configured directly on the extension. */
    static final String DEFAULT_RUNTIME = "default";
    private final Property<AwsEmulatorService> service = getObjects().property(AwsEmulatorService.class);
    private Provider<AwsEmulatorService> registeredService;
    private Project project;
    private ResourceSetModule resourceSetModule;
    private SharedRuntimeModule sharedRuntimeModule;
    private String sharedProjectPath;
    private final Set<String> sharedResourceSets = new LinkedHashSet<>();
    private static final String MODE_EXTENSION = "awsEmulatorMode";
    private static final String RUNTIME_NAME_EXTENSION = "awsEmulatorRuntimeName";
    private static final String RUNTIME_SERVICE_EXTENSION = "awsEmulatorRuntimeService";
    private final Map<String, Provider<AwsEmulatorService>> namedServices = new LinkedHashMap<>();
    private final Map<String, String> lifecycleSuffixes = new LinkedHashMap<>();
    private final NamedDomainObjectContainer<NamedEmulatorRuntime> runtimes = getObjects().domainObjectContainer(
        NamedEmulatorRuntime.class, name -> getObjects().newInstance(NamedEmulatorRuntime.class, name));
    private final NamedDomainObjectContainer<EmulatorScenario> scenarios = getObjects().domainObjectContainer(
        EmulatorScenario.class, name -> getObjects().newInstance(EmulatorScenario.class, name));
    private final NamedDomainObjectContainer<EmulatorResourceSet> resourceSets = getObjects().domainObjectContainer(
        EmulatorResourceSet.class, name -> getObjects().newInstance(EmulatorResourceSet.class, name));

    /**
     * Independent runtimes in this project. The reserved default runtime is configured on this extension.
     * Named runtimes inherit common settings, use automatic ports and separate working directories,
     * and configure external connections independently.
     */
    public NamedDomainObjectContainer<NamedEmulatorRuntime> getRuntimes() { return runtimes; }
    /** Configures named runtimes without starting Docker or connecting to external endpoints. */
    public void runtimes(Action<? super NamedDomainObjectContainer<NamedEmulatorRuntime>> action) { action.execute(runtimes); }

    boolean usesExternalRuntime(String name) {
        requireRuntime(name);
        return sharedProjectPath == null ? runtimeConfiguration(name).getExternal().getEnabled().get()
            : sharedRuntimeModule.owner().runtimes().get(name);
    }

    void requireRuntime(String name) {
        if (sharedProjectPath == null) runtimeConfiguration(name);
        else sharedRuntimeModule.owner().requireRuntime(name);
    }

    String sharedProjectPath() { return sharedProjectPath; }

    private EmulatorRuntimeConfiguration runtimeConfiguration(String name) {
        if (DEFAULT_RUNTIME.equals(name)) return this;
        if (runtimes.getNames().contains(name)) return runtimes.getByName(name);
        throw new GradleException("Unknown AWS Emulator runtime '" + name + "'. Available runtimes: default"
            + (runtimes.isEmpty() ? "" : ", " + String.join(", ", runtimes.getNames())) + ".");
    }

    private Provider<AwsEmulatorService> runtimeService(String name) {
        if (sharedProjectPath != null) return sharedRuntimeModule.runtime(name);
        runtimeConfiguration(name);
        return DEFAULT_RUNTIME.equals(name) ? registeredService : namedServices.get(name);
    }

    void registerRuntime(String name, Provider<AwsEmulatorService> runtime) {
        namedServices.put(name, runtime);
        registerLifecycle(name);
    }

    void registerLifecycle(String name) {
        String suffix = NamedEmulatorRuntime.taskSuffix(name);
        String previous = lifecycleSuffixes.putIfAbsent(suffix, name);
        if (name.equals(previous)) return;
        if (previous != null) throw new GradleException("Runtime names produce duplicate lifecycle task names: " + previous + " and " + name);
        EmulatorModule.registerLifecycleTasks(project, name);
    }

    TaskProvider<Task> lifecycleTask(String task, String name) {
        requireRuntime(name);
        if (!DEFAULT_RUNTIME.equals(name)) registerLifecycle(name);
        TaskProvider<Task> selected = project.getTasks().named(task + NamedEmulatorRuntime.taskSuffix(name));
        var lifecycle = (AbstractAwsEmulatorTask) selected.get();
        if (!lifecycle.getRuntimeName().get().equals(name)) {
            throw new GradleException("AWS Emulator lifecycle task " + lifecycle.getPath() + " uses runtime '"
                + lifecycle.getRuntimeName().get() + "' but the scenario uses '" + name
                + "'. Use the named lifecycle task when operating on another runtime alongside a scenario.");
        }
        return selected;
    }
    /** Scenario used by seedAwsEmulator and resetAwsEmulator unless -PawsEmulator.scenario is set. */
    public abstract Property<String> getDefaultScenario();
    public NamedDomainObjectContainer<EmulatorScenario> getScenarios() { return scenarios; }
    public void scenarios(Action<? super NamedDomainObjectContainer<EmulatorScenario>> action) { action.execute(scenarios); }
    /** Named definitions that can be attached to consumers or development scenarios. */
    public NamedDomainObjectContainer<EmulatorResourceSet> getResourceSets() { return resourceSets; }
    public void resourceSets(Action<? super NamedDomainObjectContainer<EmulatorResourceSet>> action) { action.execute(resourceSets); }

    void attach(Project project, Provider<AwsEmulatorService> service) {
        this.project = project;
        this.registeredService = service;
        this.service.convention(service);
        this.resourceSetModule = new ResourceSetModule(project, this);
        this.sharedRuntimeModule = new SharedRuntimeModule(project, this);
    }

    void usesRuntime(Task task) {
        // Gradle requires the original registered provider here, not a delegating Property.
        task.usesService(registeredService);
    }

    void configureRuntime(AbstractAwsEmulatorTask task) {
        task.getRuntime().convention(service);
        usesRuntime(task);
        task.dependsOn(project.provider(() -> {
            bindRuntime(task, task.getRuntimeName(), task.getRuntime());
            return List.of();
        }));
    }

    private void bindRuntime(Task task, Provider<String> name, Property<AwsEmulatorService> target) {
        Provider<AwsEmulatorService> selected = runtimeService(name.get());
        target.convention(selected);
        task.usesService(selected);
    }

    Object resolveSetup(Object setup, String runtimeName, EmulatorMode mode) {
        requireRuntime(runtimeName);
        return resourceSetModule.resolve(setup, runtimeName, mode);
    }

    /**
     * Uses an explicitly selected project's runtime and optional named resource sets in this build.
     * The owner must apply the core plugin and own its runtime directly. Its runtime settings,
     * named runtime definitions, working directories, and lifecycles are authoritative for this project's emulator tasks and
     * opted-in consumers. This method does not opt ordinary tasks into the emulator.
     *
     * <p>Selected resource sets are resolved in the owner's project and attached automatically to
     * every {@code useWith} consumer, including the Spring Boot companion's local run tasks.
     * They execute once per owner, runtime name, and mode in an invocation. Managed and development instances
     * remain separate unless the owner selects an external connection, where both modes use the
     * same endpoint. Managed cleanup waits for consumers across all participating projects.
     *
     * <p>The owner's immutable runtime metadata is resolved through a project dependency when a
     * consumer is selected. Shared setup is attached by task path, supporting Gradle Isolated Projects.
     * Configure the owner's plugin and resources in its own build script. Repeated calls may add
     * resource sets for the same owner; changing owners, selecting
     * oneself, and chaining shared owners are rejected. Consumer runtime settings are ignored.
     * Declare the core plugin with {@code apply false} in the root plugins block when participating
     * projects have different plugin classpaths, for example when mixing core and Spring Boot plugins.
     *
     * @param ownerProject absolute Gradle project path, for example {@code :local-aws}
     * @param resourceSets names of resource sets declared in the owner; omit to share only the runtime
     */
    public void useSharedRuntime(String ownerProject, String... resourceSets) {
        if (ownerProject == null || !ownerProject.startsWith(":")) {
            throw new GradleException("useSharedRuntime requires an absolute project path, for example ':local-aws'.");
        }
        Project owner = project.findProject(ownerProject);
        if (owner == null) throw new GradleException("Unknown AWS Emulator runtime owner project: " + ownerProject);
        if (owner == project) throw new GradleException("An AWS Emulator project cannot share its own runtime: " + ownerProject);
        if (sharedProjectPath != null && !sharedProjectPath.equals(ownerProject)) {
            throw new GradleException("AWS Emulator runtime for " + project.getPath() + " already selects " + sharedProjectPath
                + "; cannot select " + ownerProject + ".");
        }
        for (String name : resourceSets) {
            if (name == null || name.isBlank()) throw new GradleException("Shared AWS Emulator resource set names must not be blank.");
        }
        if (sharedProjectPath == null) sharedRuntimeModule.select(ownerProject);
        sharedProjectPath = ownerProject;
        sharedResourceSets.addAll(java.util.List.of(resourceSets));
    }

    private Object resolveConsumerSetup(Object setup, String runtimeName, EmulatorMode mode) {
        var dependencies = new ArrayList<Object>();
        if (sharedProjectPath != null) {
            for (String name : sharedResourceSets) {
                dependencies.add(sharedRuntimeModule.resourceTaskPath(name, runtimeName, mode));
            }
        }
        dependencies.add(resolveSetup(setup, runtimeName, mode));
        return dependencies;
    }

    /**
     * Configures a Test, JavaExec, or Exec task's child process using the managed mode.
     * Starts a build-owned emulator unless an external connection is enabled.
     */
    public void useWith(TaskProvider<? extends Task> taskProvider) {
        useWith(taskProvider, EmulatorMode.MANAGED);
    }

    /**
     * Connects a consumer to the selected runtime after its setup tasks complete.
     * Setup accepts resource sets (including providers) and Gradle dependency notation.
     * Resource sets create separate task instances for each runtime name and mode and run their fixtures in order.
     * Resource setup tasks must use the same mode as the consumer. When an external connection
     * is enabled, both modes use that endpoint without lifecycle management or state isolation.
     */
    public void useWith(TaskProvider<? extends Task> taskProvider, EmulatorMode mode, Object... setupTasks) {
        useWith(taskProvider, getObjects().property(EmulatorMode.class).value(mode), setupTasks);
    }

    /**
     * Lazily selects the default runtime's mode, allowing framework integrations to supply their own mode property.
     * Repeated calls update the mode and add setup dependencies without installing another startup action.
     */
    public void useWith(TaskProvider<? extends Task> taskProvider, Provider<EmulatorMode> mode, Object... setupTasks) {
        useWithRuntime(taskProvider, getObjects().property(String.class).value(DEFAULT_RUNTIME), mode, setupTasks);
    }

    /** Connects a consumer and its setup definitions to the named runtime in managed mode. */
    public void useWith(TaskProvider<? extends Task> taskProvider, String runtimeName, Object... setupTasks) {
        useWith(taskProvider, runtimeName, EmulatorMode.MANAGED, setupTasks);
    }

    /** Connects a consumer and its setup definitions to a named runtime and lifecycle mode. */
    public void useWith(TaskProvider<? extends Task> taskProvider, String runtimeName, EmulatorMode mode, Object... setupTasks) {
        useWithRuntime(taskProvider, getObjects().property(String.class).value(runtimeName),
            getObjects().property(EmulatorMode.class).value(mode), setupTasks);
    }

    /**
     * Lazily selects both the runtime name and lifecycle mode; the final selection applies to all setup definitions.
     * Uses a distinct method name so Groovy can distinguish lazy runtime selection from mode-plus-setup calls.
     */
    public void useWithRuntime(TaskProvider<? extends Task> taskProvider, Provider<String> runtimeName,
            Provider<EmulatorMode> mode, Object... setupTasks) {
        ObjectFactory objects = getObjects();
        taskProvider.configure(task -> {
            if (!(task instanceof Test) && !(task instanceof JavaExec) && !(task instanceof Exec)) {
                throw new GradleException("awsEmulator.useWith requires a Test, JavaExec, or Exec task: " + task.getPath());
            }
            if (task.getExtensions().getExtraProperties().has(MODE_EXTENSION)) {
                @SuppressWarnings("unchecked")
                Property<EmulatorMode> selected = (Property<EmulatorMode>) task.getExtensions()
                    .getExtraProperties().get(MODE_EXTENSION);
                @SuppressWarnings("unchecked")
                Property<String> selectedName = (Property<String>) task.getExtensions().getExtraProperties().get(RUNTIME_NAME_EXTENSION);
                @SuppressWarnings("unchecked")
                Property<AwsEmulatorService> runtime = (Property<AwsEmulatorService>) task.getExtensions()
                    .getExtraProperties().get(RUNTIME_SERVICE_EXTENSION);
                selected.set(mode);
                selectedName.set(runtimeName);
                task.dependsOn(project.provider(() -> {
                    bindRuntime(task, selectedName, runtime);
                    return resolveConsumerSetup(setupTasks, selectedName.get(), selected.get());
                }));
                return;
            }
            Property<EmulatorMode> selected = objects.property(EmulatorMode.class).value(mode);
            Property<String> selectedName = objects.property(String.class).value(runtimeName);
            Property<AwsEmulatorService> runtime = objects.property(AwsEmulatorService.class).convention(service);
            task.dependsOn(project.provider(() -> {
                bindRuntime(task, selectedName, runtime);
                return resolveConsumerSetup(setupTasks, selectedName.get(), selected.get());
            }));
            task.getExtensions().getExtraProperties().set(MODE_EXTENSION, selected);
            task.getExtensions().getExtraProperties().set(RUNTIME_NAME_EXTENSION, selectedName);
            task.getExtensions().getExtraProperties().set(RUNTIME_SERVICE_EXTENSION, runtime);
            usesRuntime(task);
            task.doFirst(new ConfigureEmulatorAction(runtime, selected));
            // All runtimes have live state that Gradle cannot track.
            task.getOutputs().upToDateWhen(ignored -> false);
            task.getOutputs().doNotCacheIf("Uses a live AWS Emulator", ignored -> true);
        });
    }
}
