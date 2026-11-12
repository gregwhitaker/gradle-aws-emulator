package io.github.gregwhitaker.awsemulator.aws.kinesis;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.workers.WorkerExecutor;
import javax.inject.Inject;
import java.util.List;

/** Runs Kinesis SDK operations in a worker JVM so its JSON setting cannot affect other build clients. */
public abstract class AbstractKinesisTask extends AbstractAwsResourceTask {
    @Inject protected abstract WorkerExecutor getWorkerExecutor();

    protected final void runKinesis(String operation, String stream, int shards, int wait,
                                    List<String> keys, List<String> data) {
        var connection = connection();
        var queue = getWorkerExecutor().processIsolation(spec -> {
            spec.getForkOptions().systemProperty("aws.cborEnabled", "false");
            spec.getForkOptions().setMaxHeapSize("256m");
        });
        queue.submit(KinesisWork.class, parameters -> {
            parameters.getOperation().set(operation);
            parameters.getStream().set(stream);
            parameters.getShards().set(shards);
            parameters.getWaitSeconds().set(wait);
            parameters.getTimeoutSeconds().set(getApiCallTimeoutSeconds());
            parameters.getPort().set(connection.port());
            parameters.getRegion().set(connection.region());
            parameters.getConnectionDirectory().set(connection.directory().toFile());
            parameters.getKeys().set(keys);
            parameters.getData().set(data);
        });
        getWorkerExecutor().await();
    }
}
