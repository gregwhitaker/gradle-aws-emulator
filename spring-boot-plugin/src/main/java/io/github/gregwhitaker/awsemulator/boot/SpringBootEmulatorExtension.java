package io.github.gregwhitaker.awsemulator.boot;

import io.github.gregwhitaker.awsemulator.emulator.EmulatorResourceSet;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Settings in {@code awsEmulator.springBoot}, shared by bootRunLocal and bootRunLocalDebug.
 * Ordinary bootRun remains independently configured.
 */
public abstract class SpringBootEmulatorExtension {
    /** DEVELOPMENT (default) retains the emulator after the application exits; MANAGED cleans it up. */
    public abstract Property<EmulatorMode> getMode();
    /** Active Spring profiles, defaulting to local. An empty list supplies no profile argument. */
    public abstract ListProperty<String> getProfiles();
    /** Additional JVM arguments for both local tasks. */
    public abstract ListProperty<String> getJvmArgs();
    /** Additional task names or paths to execute before either local task. */
    public abstract ListProperty<String> getSetupTasks();
    /** Reusable definitions to provision and seed in the local application's selected mode. */
    public abstract ListProperty<EmulatorResourceSet> getResourceSets();
    /** Automatically run resource provisioning tasks before the application; defaults to true. */
    public abstract Property<Boolean> getAutoCreateResources();
    /** Local task names or paths to omit from automatic discovery. Explicit dependencies still apply. */
    public abstract ListProperty<String> getExcludedSetupTasks();
    /** Loopback debugger port, defaulting to 5005. */
    public abstract Property<Integer> getDebugPort();
    /** Whether bootRunLocalDebug waits for a debugger; defaults to true. */
    public abstract Property<Boolean> getDebugSuspend();
}
