package io.github.gregwhitaker.awsemulator.aws.kms;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.*;
import org.gradle.api.GradleException;
import org.gradle.api.provider.*;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import java.util.List;
import java.util.Map;

/** Schedules key deletion using AWS semantics; there is no immediate key deletion option. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class ScheduleKmsKeyDeletionTask extends AbstractAwsResourceTask {
    public ScheduleKmsKeyDeletionTask() { getPendingWindowInDays().convention(30); }
    @Input public abstract Property<String> getKeyId();
    @Option(option = "keyId", description = "KeyId") public void setKeyIdOption(String value) { getKeyId().set(value); }
    @Input public abstract Property<Integer> getPendingWindowInDays();
    @TaskAction
    public void executeTask() {
        String key = ServiceSupport.text(getKeyId().get(), "keyId");
        int days = getPendingWindowInDays().get();
        if (days < 7 || days > 30) throw new GradleException("pendingWindowInDays must be between 7 and 30.");
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).kms()) {
            new KmsOperations().scheduleDeletion(client, key, days);
            getLogger().lifecycle("KMS key deletion scheduled: {}", key);
        }
    }
}
