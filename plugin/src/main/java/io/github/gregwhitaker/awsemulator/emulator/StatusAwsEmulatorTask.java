package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Reports the development emulator status. */
@DisableCachingByDefault(because = "Manages an external Docker runtime")
public abstract class StatusAwsEmulatorTask extends AbstractAwsEmulatorTask {
    @TaskAction
    public void status() {
        getLogger().lifecycle(getRuntime().get().status());
    }
}
