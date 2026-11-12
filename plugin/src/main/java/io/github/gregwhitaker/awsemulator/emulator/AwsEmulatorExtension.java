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
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;
import javax.inject.Inject;

/** Configuration for the AWS Emulator runtime and its opted-in consumers. */
public abstract class AwsEmulatorExtension {
    private Provider<AwsEmulatorService> service;
    private Project project;
    private ResourceSetModule resourceSetModule;
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
        this.service = service;
        this.resourceSetModule = new ResourceSetModule(project, this);
    }

    Object resolveSetup(Object setup, EmulatorMode mode) { return resourceSetModule.resolve(setup, mode); }

    /** Starts a build-owned emulator for this Test or JavaExec task, and configures its forked JVM. */
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
            if (!(task instanceof Test) && !(task instanceof JavaExec)) {
                throw new GradleException("awsEmulator.useWith requires a Test or JavaExec task: " + task.getPath());
            }
            if (task.getExtensions().getExtraProperties().has(MODE_EXTENSION)) {
                @SuppressWarnings("unchecked")
                Property<EmulatorMode> selected = (Property<EmulatorMode>) task.getExtensions()
                    .getExtraProperties().get(MODE_EXTENSION);
                selected.set(mode);
                task.dependsOn(project.provider(() -> resolveSetup(setupTasks, selected.get())));
                return;
            }
            Property<EmulatorMode> selected = objects.property(EmulatorMode.class).value(mode);
            task.dependsOn(project.provider(() -> resolveSetup(setupTasks, selected.get())));
            task.getExtensions().getExtraProperties().set(MODE_EXTENSION, selected);
            task.usesService(runtime);
            task.doFirst(new ConfigureEmulatorAction(runtime, selected));
            // Both runtimes have external state that Gradle cannot track.
            task.getOutputs().upToDateWhen(ignored -> false);
            task.getOutputs().doNotCacheIf("Uses a live AWS Emulator", ignored -> true);
        });
    }
}
