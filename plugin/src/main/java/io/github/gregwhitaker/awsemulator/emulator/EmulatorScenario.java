package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.Named;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A named development dataset, defined using the plugin's typed resource tasks. */
public class EmulatorScenario implements Named {
    private final String name;
    private final List<TaskProvider<? extends AbstractAwsResourceTask>> provision = new ArrayList<>();
    private final List<TaskProvider<? extends AbstractAwsResourceTask>> seed = new ArrayList<>();
    private final List<Provider<? extends EmulatorResourceSet>> resources = new ArrayList<>();

    @Inject public EmulatorScenario(String name) { this.name = name; }
    @Override public String getName() { return name; }

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
