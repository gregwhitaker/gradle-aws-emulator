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
import org.gradle.api.Named;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.Property;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.TaskProvider;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A named development dataset, defined using the plugin's typed resource tasks. */
public class EmulatorScenario implements Named {
    private final String name;
    private final Property<String> runtimeName;
    private final List<TaskProvider<? extends AbstractAwsResourceTask>> provision = new ArrayList<>();
    private final List<TaskProvider<? extends AbstractAwsResourceTask>> seed = new ArrayList<>();
    private final List<Provider<? extends EmulatorResourceSet>> resources = new ArrayList<>();

    @Inject public EmulatorScenario(String name, ObjectFactory objects) {
        this.name = name;
        runtimeName = objects.property(String.class).convention(AwsEmulatorExtension.DEFAULT_RUNTIME);
    }
    @Override public String getName() { return name; }

    /** Runtime whose development instance receives this scenario. Defaults to default. */
    public Property<String> getRuntimeName() { return runtimeName; }

    /** Applies reusable definitions to the development instance before this scenario's explicit tasks. */
    @SafeVarargs public final void resources(Provider<? extends EmulatorResourceSet>... sets) {
        resources.addAll(Arrays.asList(sets));
    }

    /** Resources to create before loading any fixtures. Use dependsOn for dependencies between resources. */
    @SafeVarargs public final void provision(TaskProvider<? extends AbstractAwsResourceTask>... tasks) {
        provision.addAll(Arrays.asList(tasks));
    }

    /** Fixture tasks to execute in declaration order after all provisioning succeeds. */
    @SafeVarargs public final void seed(TaskProvider<? extends AbstractAwsResourceTask>... tasks) {
        seed.addAll(Arrays.asList(tasks));
    }

    List<TaskProvider<? extends AbstractAwsResourceTask>> provisioning() { return List.copyOf(provision); }
    List<TaskProvider<? extends AbstractAwsResourceTask>> seeding() { return List.copyOf(seed); }
    List<Provider<? extends EmulatorResourceSet>> resources() { return List.copyOf(resources); }
}
