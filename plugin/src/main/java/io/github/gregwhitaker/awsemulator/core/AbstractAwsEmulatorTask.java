package io.github.gregwhitaker.awsemulator.core;

import io.github.gregwhitaker.awsemulator.emulator.AwsEmulatorService;
import org.gradle.api.DefaultTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.work.DisableCachingByDefault;

/** Shared service binding for tasks that consume this project's emulator. */
@DisableCachingByDefault(because = "Operates on a live emulator runtime")
public abstract class AbstractAwsEmulatorTask extends DefaultTask {
    @Internal
    public abstract Property<AwsEmulatorService> getRuntime();
}
