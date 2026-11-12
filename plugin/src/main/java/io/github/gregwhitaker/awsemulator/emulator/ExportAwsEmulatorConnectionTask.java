package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Starts or reuses development and writes connection.sh and connection.ps1 in its runtime home. */
@DisableCachingByDefault(because = "Exports an execution-time connection to a live emulator")
public abstract class ExportAwsEmulatorConnectionTask extends AbstractAwsEmulatorTask {
    @TaskAction
    public void exportConnection() {
        getLogger().lifecycle(getRuntime().get().exportConnection());
    }
}
