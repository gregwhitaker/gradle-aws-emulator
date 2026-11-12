package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Restarts the development emulator with empty state. */
@DisableCachingByDefault(because = "Manages an external Docker runtime")
public abstract class RestartAwsEmulatorTask extends AbstractAwsEmulatorTask {
    @TaskAction
    public void restart() {
        getLogger().lifecycle(getRuntime().get().restart());
    }
}
