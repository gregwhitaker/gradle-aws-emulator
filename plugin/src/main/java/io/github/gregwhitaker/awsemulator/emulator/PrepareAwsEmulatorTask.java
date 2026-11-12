package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Builds or locates the configured Docker image. */
@DisableCachingByDefault(because = "Manages an external Docker runtime")
public abstract class PrepareAwsEmulatorTask extends AbstractAwsEmulatorTask {
    @TaskAction
    public void prepare() {
        getLogger().lifecycle(getRuntime().get().prepare());
    }
}
