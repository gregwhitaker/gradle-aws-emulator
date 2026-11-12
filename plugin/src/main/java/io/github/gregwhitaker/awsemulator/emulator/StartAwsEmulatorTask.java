package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Starts the persistent development emulator. */
@DisableCachingByDefault(because = "Manages an external Docker runtime")
public abstract class StartAwsEmulatorTask extends AbstractAwsEmulatorTask {
    @TaskAction
    public void start() {
        getLogger().lifecycle(getRuntime().get().start());
    }
}
