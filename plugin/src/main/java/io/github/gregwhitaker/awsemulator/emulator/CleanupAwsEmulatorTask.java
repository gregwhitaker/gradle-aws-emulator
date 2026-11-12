package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/** Archives logs and removes stale managed containers owned by the selected runtime's project. */
@DisableCachingByDefault(because = "Manages live Docker and emulator state")
public abstract class CleanupAwsEmulatorTask extends AbstractAwsEmulatorTask {
    public CleanupAwsEmulatorTask() {
        getReportOnly().convention(false);
    }

    /** When true, lists eligible containers without removing them. Defaults to false. */
    @Input
    @Option(option = "report-only", description = "List stale managed containers without removing them.")
    public abstract Property<Boolean> getReportOnly();

    @TaskAction
    public void cleanup() {
        getLogger().lifecycle(getRuntime().get().cleanup(getReportOnly().get()));
    }
}
