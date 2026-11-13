/*
 * Copyright 2026 Greg Whitaker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.gregwhitaker.awsemulator.aws.kinesis;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.workers.WorkerExecutor;
import org.gradle.work.DisableCachingByDefault;
import javax.inject.Inject;
import java.util.List;

/** Runs Kinesis SDK operations in a worker JVM so its JSON setting cannot affect other build clients. */
@DisableCachingByDefault(because = "Uses live emulator resources")
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
            parameters.getEndpoint().set(connection.endpoint());
            parameters.getCredentialsFile().set(connection.credentials().toFile());
            parameters.getCertificateFile().set(connection.certificate().toFile());
            parameters.getRegion().set(connection.region());
            parameters.getConnectionDirectory().set(connection.directory().toFile());
            parameters.getKeys().set(keys);
            parameters.getData().set(data);
        });
        getWorkerExecutor().await();
    }
}
