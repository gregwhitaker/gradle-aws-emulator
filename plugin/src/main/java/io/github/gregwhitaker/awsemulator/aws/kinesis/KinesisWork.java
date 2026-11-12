package io.github.gregwhitaker.awsemulator.aws.kinesis;

import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.internal.runtime.EmulatorConnection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.*;
import org.gradle.api.logging.Logging;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry;
import java.util.ArrayList;

/** Execution boundary for Kinesis's JVM-scoped wire-format configuration. */
public abstract class KinesisWork implements WorkAction<KinesisWork.Parameters> {
    public interface Parameters extends WorkParameters {
        Property<String> getOperation();
        Property<String> getStream();
        Property<String> getRegion();
        Property<Integer> getPort();
        Property<Integer> getShards();
        Property<Integer> getWaitSeconds();
        Property<Integer> getTimeoutSeconds();
        DirectoryProperty getConnectionDirectory();
        ListProperty<String> getKeys();
        ListProperty<String> getData();
    }
    @Override public void execute() {
        var p = getParameters();
        var connection = new EmulatorConnection(p.getPort().get(), p.getRegion().get(), p.getConnectionDirectory().get().getAsFile().toPath());
        var log = Logging.getLogger(KinesisWork.class);
        try (var client = new AwsClientFactory(connection, p.getTimeoutSeconds().get()).kinesis()) {
            var ops = new KinesisOperations();
            switch (p.getOperation().get()) {
                case "create" -> ops.create(client, p.getStream().get(), p.getShards().get(), p.getWaitSeconds().get());
                case "delete" -> ops.delete(client, p.getStream().get(), p.getWaitSeconds().get());
                case "list" -> ops.list(client).forEach(name -> log.lifecycle("{}", name));
                case "publish" -> {
                    var records = new ArrayList<PutRecordsRequestEntry>();
                    var keys = p.getKeys().get(); var data = p.getData().get();
                    for (int i = 0; i < keys.size(); i++) records.add(PutRecordsRequestEntry.builder()
                        .partitionKey(keys.get(i)).data(SdkBytes.fromUtf8String(data.get(i))).build());
                    ops.publish(client, p.getStream().get(), records);
                    log.lifecycle("Published {} Kinesis records.", records.size());
                }
                default -> throw new IllegalArgumentException("Unknown Kinesis operation.");
            }
        }
    }
}
