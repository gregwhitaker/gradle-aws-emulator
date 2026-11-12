package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Reports Docker, image, port, runtime, connection-file, and HTTPS health diagnostics without starting an instance. */
@DisableCachingByDefault(because = "Inspects live Docker and emulator state")
public abstract class DoctorAwsEmulatorTask extends AbstractAwsEmulatorTask {
    @TaskAction
    public void diagnose() {
        getLogger().lifecycle(getRuntime().get().doctor());
    }
}
