package io.github.gregwhitaker.awsemulator.aws;

import io.github.gregwhitaker.awsemulator.core.AbstractAwsEmulatorTask;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import io.github.gregwhitaker.awsemulator.model.EmulatorMode;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.work.DisableCachingByDefault;

/** Shared configuration for provisioning resources inside this project's emulator. */
@DisableCachingByDefault(because = "Provisions live emulator resources")
public abstract class AbstractAwsResourceTask extends AbstractAwsEmulatorTask {
    public AbstractAwsResourceTask() {
        getMode().convention(EmulatorMode.MANAGED);
        getApiCallTimeoutSeconds().convention(30);
        getOutputs().upToDateWhen(ignored -> false);
        getOutputs().doNotCacheIf("Provisions live emulator resources", ignored -> true);
    }

    /** Managed tests and provisioning must select the same runtime. Defaults to MANAGED. */
    @Input
    public abstract Property<EmulatorMode> getMode();

    /** Deadline for each SDK operation, including retries. Defaults to 30 seconds. */
    @Input
    public abstract Property<Integer> getApiCallTimeoutSeconds();

    protected final EmulatorConnection connection() {
        if (getApiCallTimeoutSeconds().get() < 1) throw new GradleException("apiCallTimeoutSeconds must be positive.");
        return getMode().get() == EmulatorMode.MANAGED
            ? getRuntime().get().managedConnection() : getRuntime().get().developmentConnection();
    }
}
