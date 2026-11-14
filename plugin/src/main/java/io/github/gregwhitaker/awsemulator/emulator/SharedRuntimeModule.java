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

import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorSettings;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.provider.Provider;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exchanges immutable owner metadata through a project dependency, without reading another project's model. */
final class SharedRuntimeModule {
    private static final String ELEMENTS = "awsEmulatorRuntimeElements";
    private static final String RESOURCE_TASK_PREFIX = "awsEmulatorResourceSet_";
    private static final Attribute<String> DIRECTORY = attribute("directory");
    private static final Attribute<String> SHARED_OWNER = attribute("shared-owner");
    private static final Attribute<String> RUNTIMES = attribute("runtimes");
    private static final Attribute<String> RESOURCE_SETS = attribute("resource-sets");
    private static final List<String> LIFECYCLE_TASKS = List.of("prepareAwsEmulator", "startAwsEmulator",
        "stopAwsEmulator", "restartAwsEmulator", "statusAwsEmulator", "logsAwsEmulator",
        "doctorAwsEmulator", "cleanupAwsEmulator", "exportAwsEmulatorConnection");

    private final Project project;
    private final AwsEmulatorExtension extension;
    private Configuration incoming;
    private String ownerPath;

    SharedRuntimeModule(Project project, AwsEmulatorExtension extension) {
        this.project = project;
        this.extension = extension;
        project.getConfigurations().create(ELEMENTS, configuration -> {
            configuration.setCanBeResolved(false);
            configuration.setCanBeConsumed(true);
            configuration.setVisible(false);
            configuration.getAttributes().attribute(DIRECTORY, project.getProjectDir().getAbsolutePath());
            configuration.getAttributes().attributeProvider(SHARED_OWNER,
                project.provider(() -> extension.sharedProjectPath() == null ? "" : extension.sharedProjectPath()));
            configuration.getAttributes().attributeProvider(RUNTIMES, project.provider(() -> {
                var names = new LinkedHashMap<String, Boolean>();
                names.put(AwsEmulatorExtension.DEFAULT_RUNTIME, extension.getExternal().getEnabled().get());
                extension.getRuntimes().forEach(runtime -> names.put(runtime.getName(), runtime.getExternal().getEnabled().get()));
                return names.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(","));
            }));
            configuration.getAttributes().attributeProvider(RESOURCE_SETS,
                project.provider(() -> String.join(",", extension.getResourceSets().getNames())));
        });
        project.getTasks().addRule("Pattern: awsEmulatorResourceSet_<runtime>_<mode>_<set>: provision an owner's shared resource set", this::resourceTask);
        project.getTasks().addRule("Pattern: <lifecycleTask><Runtime>: operate on a shared owner's named runtime", this::lifecycleTask);
    }

    void select(String ownerPath) {
        this.ownerPath = ownerPath;
        incoming = project.getConfigurations().create("awsEmulatorSharedRuntime", configuration -> {
            configuration.setCanBeConsumed(false);
            configuration.setCanBeResolved(true);
            configuration.setTransitive(false);
            configuration.setVisible(false);
        });
        project.getDependencies().add(incoming.getName(),
            project.getDependencies().project(Map.of("path", ownerPath, "configuration", ELEMENTS)));
    }

    Owner owner() {
        // Resolution evaluates the owner through Gradle's dependency model and records the configuration-cache edge.
        var dependencies = incoming.getIncoming().getResolutionResult().getRootComponent().get().getDependencies();
        for (var dependency : dependencies) {
            if (dependency instanceof UnresolvedDependencyResult unresolved) {
                throw new GradleException("AWS Emulator runtime owner " + ownerPath
                    + " must apply 'io.github.gregwhitaker.aws-emulator' and expose its runtime configuration.",
                    unresolved.getFailure());
            }
            if (dependency instanceof ResolvedDependencyResult resolved) {
                var attributes = resolved.getResolvedVariant().getAttributes();
                String shared = attributes.getAttribute(SHARED_OWNER);
                if (shared == null) throw new GradleException("Invalid AWS Emulator runtime metadata from " + ownerPath);
                if (!shared.isEmpty()) throw new GradleException("AWS Emulator runtime owner " + ownerPath
                    + " already selects " + shared + ". Select the owner directly; sharing cannot be chained or cyclic.");
                var runtimes = new LinkedHashMap<String, Boolean>();
                for (String entry : attributes.getAttribute(RUNTIMES).split(",")) {
                    String[] parts = entry.split("=", 2);
                    runtimes.put(parts[0], Boolean.parseBoolean(parts[1]));
                }
                String resources = attributes.getAttribute(RESOURCE_SETS);
                Set<String> sets = resources.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(resources.split(",")));
                return new Owner(attributes.getAttribute(DIRECTORY), Map.copyOf(runtimes), sets);
            }
        }
        throw new GradleException("Missing AWS Emulator runtime metadata from " + ownerPath);
    }

    Provider<AwsEmulatorService> runtime(String name) {
        Owner owner = owner();
        owner.requireRuntime(name);
        String identity = owner.directory();
        if (!AwsEmulatorExtension.DEFAULT_RUNTIME.equals(name)) identity += "\n" + name;
        String serviceName = "awsEmulator-" + EmulatorSettings.hash(identity);
        var registration = project.getGradle().getSharedServices().getRegistrations().findByName(serviceName);
        if (registration == null) throw new GradleException("AWS Emulator runtime owner " + ownerPath
            + " did not register runtime '" + name + "'.");
        // Build services are build-scoped; Gradle supports retrieving a registration by name across projects.
        @SuppressWarnings("unchecked")
        Provider<AwsEmulatorService> service = (Provider<AwsEmulatorService>) (Provider<?>) registration.getService();
        return service;
    }

    String resourceTaskPath(String set, String runtime, EmulatorMode mode) {
        Owner owner = owner();
        owner.requireRuntime(runtime);
        if (!owner.resourceSets().contains(set)) throw new GradleException("Unknown AWS Emulator resource set '"
            + set + "' in owner " + ownerPath + ".");
        return (ownerPath.equals(":") ? ":" : ownerPath + ":") + RESOURCE_TASK_PREFIX + runtime + "_" + mode + "_" + set;
    }

    private void resourceTask(String name) {
        if (!name.startsWith(RESOURCE_TASK_PREFIX)) return;
        String[] selection = name.substring(RESOURCE_TASK_PREFIX.length()).split("_", 3);
        if (selection.length != 3 || !extension.getResourceSets().getNames().contains(selection[2])) return;
        EmulatorMode mode;
        try { mode = EmulatorMode.valueOf(selection[1]); }
        catch (IllegalArgumentException ignored) { return; }
        extension.requireRuntime(selection[0]);
        // Task rules are invoked only for a requested name and must materialize that task before returning.
        project.getTasks().register(name, task -> {
            task.setGroup("AWS Emulator - Resources");
            task.setDescription("Applies shared resource set '" + selection[2] + "' to " + selection[0] + " / " + mode + ".");
            task.dependsOn(project.provider(() ->
                extension.resolveSetup(extension.getResourceSets().named(selection[2]), selection[0], mode, task.getPath())));
        }).get();
    }

    private void lifecycleTask(String taskName) {
        if (incoming == null) return;
        for (String prefix : LIFECYCLE_TASKS) {
            if (!taskName.startsWith(prefix) || taskName.equals(prefix)) continue;
            String suffix = taskName.substring(prefix.length());
            for (String name : owner().runtimes().keySet()) {
                if (NamedEmulatorRuntime.taskSuffix(name).equals(suffix)) {
                    extension.registerLifecycle(name);
                    project.getTasks().named(taskName).get();
                    return;
                }
            }
        }
    }

    private static Attribute<String> attribute(String name) {
        return Attribute.of("io.github.gregwhitaker.aws-emulator." + name, String.class);
    }

    record Owner(String directory, Map<String, Boolean> runtimes, Set<String> resourceSets) {
        void requireRuntime(String name) {
            if (!runtimes.containsKey(name)) throw new GradleException("Unknown AWS Emulator runtime '" + name
                + "'. Available runtimes: " + String.join(", ", new java.util.TreeSet<>(runtimes.keySet())) + ".");
        }
    }
}
