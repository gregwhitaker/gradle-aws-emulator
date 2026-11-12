package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Stops the persistent development emulator. */
@DisableCachingByDefault(because = "Manages an external Docker runtime")
public abstract class StopAwsEmulatorTask extends AbstractAwsEmulatorTask {
    @TaskAction
    public void stop() {
        getLogger().lifecycle(getRuntime().get().stop());
    }
}
