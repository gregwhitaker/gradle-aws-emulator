package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Displays recent development emulator logs. */
@DisableCachingByDefault(because = "Manages an external Docker runtime")
public abstract class LogsAwsEmulatorTask extends AbstractAwsEmulatorTask {
    @TaskAction
    public void logs() {
        getLogger().lifecycle(getRuntime().get().logs());
    }
}
