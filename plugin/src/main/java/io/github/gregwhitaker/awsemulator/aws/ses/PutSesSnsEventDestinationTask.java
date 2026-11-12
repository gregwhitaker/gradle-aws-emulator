package io.github.gregwhitaker.awsemulator.aws.ses;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.aws.internal.ServiceSupport;
import io.github.gregwhitaker.awsemulator.core.annotation.AwsEmulatorSetupTask;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.core.exception.SdkException;

import java.util.List;

/** Creates or reuses an enabled SNS event destination; changing it requires overwrite=true. */
@AwsEmulatorSetupTask
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class PutSesSnsEventDestinationTask extends AbstractSesConfigurationSetTask {
    public PutSesSnsEventDestinationTask() {
        getMatchingEventTypes().convention(List.of("SEND", "DELIVERY", "BOUNCE", "COMPLAINT"));
        getOverwrite().convention(false);
    }

    /** Name of the destination within the configuration set. */
    @Input public abstract Property<String> getEventDestinationName();
    /** ARN of an existing standard SNS topic in this emulator. */
    @Input public abstract Property<String> getTopicArn();
    /** SES event types; defaults to SEND, DELIVERY, BOUNCE, and COMPLAINT. */
    @Input public abstract ListProperty<String> getMatchingEventTypes();
    /** Allows replacing an existing destination with different settings. */
    @Input public abstract Property<Boolean> getOverwrite();

    @TaskAction public void put() {
        String set = configurationSetName();
        String name = SesOperations.name(getEventDestinationName().get(), "eventDestinationName");
        String arn = ServiceSupport.name(getTopicArn().get(), "arn:[a-z0-9-]+:sns:[a-z0-9-]+:[0-9]{12}:[a-zA-Z0-9_-]{1,256}", "standard SNS topic ARN");
        var types = SesOperations.eventTypes(getMatchingEventTypes().get());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sesV2()) {
            new SesOperations().putSnsDestination(client, set, name, arn, types, getOverwrite().get());
            getLogger().lifecycle("SES event destination ready: {} / {}", set, name);
        } catch (SdkException error) { throw SesOperations.failure("Configuring SES event destination", error); }
    }
}
