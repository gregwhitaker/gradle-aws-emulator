package io.github.gregwhitaker.awsemulator.boot;

import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/** Settings shared by bootRunLocal and bootRunLocalDebug; ordinary bootRun remains independently configured. */
public abstract class SpringBootEmulatorExtension {
    /** DEVELOPMENT (default) retains the emulator after the application exits; MANAGED cleans it up. */
    public abstract Property<EmulatorMode> getMode();
    /** Active Spring profiles, defaulting to local. An empty list supplies no profile argument. */
    public abstract ListProperty<String> getProfiles();
    /** Additional JVM arguments for both local tasks. */
    public abstract ListProperty<String> getJvmArgs();
    /** Gradle task names or paths to execute before either local task; resources must use the same mode. */
    public abstract ListProperty<String> getSetupTasks();
    /** Loopback debugger port, defaulting to 5005. */
    public abstract Property<Integer> getDebugPort();
    /** Whether bootRunLocalDebug waits for a debugger; defaults to true. */
    public abstract Property<Boolean> getDebugSuspend();
}
