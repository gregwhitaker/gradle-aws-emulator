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

import io.github.gregwhitaker.awsemulator.internal.ConfigureEmulatorAction;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.GradleException;
import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** Configuration for the AWS Emulator runtime and its opted-in consumers. */
public abstract class AwsEmulatorExtension {
    private final Property<AwsEmulatorService> service = getObjects().property(AwsEmulatorService.class);
    private Provider<AwsEmulatorService> registeredService;
    private final Set<Task> runtimeConsumers = new LinkedHashSet<>();
    private Project project;
    private ResourceSetModule resourceSetModule;
    private AwsEmulatorExtension runtimeOwner;
    private String sharedProjectPath;
    private boolean selectedAsOwner;
    private final Set<String> sharedResourceSets = new LinkedHashSet<>();
    private static final String MODE_EXTENSION = "awsEmulatorMode";
    private final NamedDomainObjectContainer<EmulatorScenario> scenarios = getObjects().domainObjectContainer(
        EmulatorScenario.class, name -> getObjects().newInstance(EmulatorScenario.class, name));
    private final NamedDomainObjectContainer<EmulatorResourceSet> resourceSets = getObjects().domainObjectContainer(
        EmulatorResourceSet.class, name -> getObjects().newInstance(EmulatorResourceSet.class, name));

    @Inject protected abstract ObjectFactory getObjects();

    public abstract Property<String> getEmulatorVersion();
    /** Optional existing Docker image; bypasses the generated Dockerfile. */
    public abstract Property<String> getImage();
    /** Docker platform: auto, linux/amd64, or linux/arm64. */
    public abstract Property<String> getPlatform();
    /** Port shared by the host and container. Zero asks the plugin to select a port. */
    public abstract Property<Integer> getPort();
    public abstract Property<String> getRegion();
    public abstract Property<Boolean> getTelemetryEnabled();
    public abstract Property<String> getDockerExecutable();
    public abstract Property<String> getMemoryLimit();
    public abstract Property<Integer> getStartupTimeoutSeconds();
    public abstract Property<Integer> getCommandTimeoutSeconds();
    public abstract Property<Integer> getBuildTimeoutSeconds();
    public abstract DirectoryProperty getWorkingDirectory();
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
    }

    Provider<AwsEmulatorService> runtime() { return service; }

    void usesRuntime(Task task) {
        runtimeConsumers.add(task);
        // Gradle requires the original registered provider here, not a delegating Property.
        task.usesService(registeredService);
    }

    Object resolveSetup(Object setup, EmulatorMode mode) { return resourceSetModule.resolve(setup, mode); }

    /**
     * Uses an explicitly selected project's runtime and optional named resource sets in this build.
     * The owner must apply the core plugin and own its runtime directly. Its runtime settings,
     * working directory, and lifecycle are authoritative for this project's emulator tasks and
     * opted-in consumers. This method does not opt ordinary tasks into the emulator.
     *
     * <p>Selected resource sets are resolved in the owner's project and attached automatically to
     * every {@code useWith} consumer, including the Spring Boot companion's local run tasks.
     * They execute once per owner and mode in an invocation. Managed and development instances
     * remain separate. Managed cleanup waits for consumers across all participating projects.
     *
     * <p>The owner is evaluated when selected. Configure its plugin and resources in its own build
     * script. Repeated calls may add resource sets for the same owner; changing owners, selecting
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
        if (selectedAsOwner) throw new GradleException("AWS Emulator runtime owner " + project.getPath()
            + " is already selected by another project and cannot useSharedRuntime. Select the owner directly; sharing cannot be chained.");
        if (sharedProjectPath != null && !sharedProjectPath.equals(ownerProject)) {
            throw new GradleException("AWS Emulator runtime for " + project.getPath() + " already selects " + sharedProjectPath
                + "; cannot select " + ownerProject + ".");
        }
        for (String name : resourceSets) {
            if (name == null || name.isBlank()) throw new GradleException("Shared AWS Emulator resource set names must not be blank.");
        }
        sharedProjectPath = ownerProject;
        requireDirectOwner(owner.getExtensions().findByType(AwsEmulatorExtension.class), ownerProject);
        project.evaluationDependsOn(ownerProject);
        AwsEmulatorExtension selected = owner.getExtensions().findByType(AwsEmulatorExtension.class);
        if (selected == null && owner.getPluginManager().hasPlugin("io.github.gregwhitaker.aws-emulator")) {
            throw new GradleException("AWS Emulator runtime owner " + ownerProject + " loads a different copy of the core plugin. "
                + "Declare id(\"io.github.gregwhitaker.aws-emulator\") apply false in the root project's plugins block "
                + "so all sharing projects use the same core plugin version and classpath.");
        }
        if (selected == null) throw new GradleException("AWS Emulator runtime owner " + ownerProject
            + " must apply 'io.github.gregwhitaker.aws-emulator'.");
        requireDirectOwner(selected, ownerProject);
        selected.selectedAsOwner = true;
        runtimeOwner = selected;
        registeredService = selected.registeredService;
        service.set(selected.runtime());
        // Consumers may already have been realized by another plugin or the build script.
        // Previously declared local services stay unused and never create a runtime.
        for (Task consumer : runtimeConsumers) consumer.usesService(registeredService);
        sharedResourceSets.addAll(java.util.List.of(resourceSets));
    }

    private static void requireDirectOwner(AwsEmulatorExtension owner, String path) {
        if (owner != null && owner.sharedProjectPath != null) {
            throw new GradleException("AWS Emulator runtime owner " + path + " already selects " + owner.sharedProjectPath
                + ". Select the owner directly; sharing cannot be chained or cyclic.");
        }
    }

    private Object resolveConsumerSetup(Object setup, EmulatorMode mode) {
        var dependencies = new ArrayList<Object>();
        if (runtimeOwner != null) {
            for (String name : sharedResourceSets) {
                if (!runtimeOwner.getResourceSets().getNames().contains(name)) {
                    throw new GradleException("Unknown AWS Emulator resource set '" + name + "' in owner " + sharedProjectPath + ".");
                }
                dependencies.add(runtimeOwner.resolveSetup(runtimeOwner.getResourceSets().named(name), mode));
            }
        }
        dependencies.add(resolveSetup(setup, mode));
        return dependencies;
    }

    /** Starts a build-owned emulator for a Test, JavaExec, or Exec task and configures its child process. */
    public void useWith(TaskProvider<? extends Task> taskProvider) {
        useWith(taskProvider, EmulatorMode.MANAGED);
    }

    /**
     * Connects a consumer to the selected runtime after its setup tasks complete.
     * Setup accepts resource sets (including providers) and Gradle dependency notation.
     * Resource sets create separate task instances for each mode and run their fixtures in order.
     * Resource setup tasks must use the same mode as the consumer.
     */
    public void useWith(TaskProvider<? extends Task> taskProvider, EmulatorMode mode, Object... setupTasks) {
        useWith(taskProvider, getObjects().property(EmulatorMode.class).value(mode), setupTasks);
    }

    /**
     * Lazily selects a runtime, allowing framework integrations to supply their own mode property.
     * Repeated calls update the mode and add setup dependencies without installing another startup action.
     */
    public void useWith(TaskProvider<? extends Task> taskProvider, Provider<EmulatorMode> mode, Object... setupTasks) {
        Provider<AwsEmulatorService> runtime = service;
        ObjectFactory objects = getObjects();
        taskProvider.configure(task -> {
            if (!(task instanceof Test) && !(task instanceof JavaExec) && !(task instanceof Exec)) {
                throw new GradleException("awsEmulator.useWith requires a Test, JavaExec, or Exec task: " + task.getPath());
            }
            if (task.getExtensions().getExtraProperties().has(MODE_EXTENSION)) {
                @SuppressWarnings("unchecked")
                Property<EmulatorMode> selected = (Property<EmulatorMode>) task.getExtensions()
                    .getExtraProperties().get(MODE_EXTENSION);
                selected.set(mode);
                task.dependsOn(project.provider(() -> resolveConsumerSetup(setupTasks, selected.get())));
                return;
            }
            Property<EmulatorMode> selected = objects.property(EmulatorMode.class).value(mode);
            task.dependsOn(project.provider(() -> resolveConsumerSetup(setupTasks, selected.get())));
            task.getExtensions().getExtraProperties().set(MODE_EXTENSION, selected);
            usesRuntime(task);
            task.doFirst(new ConfigureEmulatorAction(runtime, selected));
            // Both runtimes have external state that Gradle cannot track.
            task.getOutputs().upToDateWhen(ignored -> false);
            task.getOutputs().doNotCacheIf("Uses a live AWS Emulator", ignored -> true);
        });
    }
}
