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

package io.github.gregwhitaker.awsemulator.aws.sqs;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Resolves all targets before publishing, without retrying an entire partially sent input. */
final class SqsMessagePublisher {
    long publish(SqsClient client, List<String> names, List<Path> files, String group) {
        var urls = new SqsQueueOperations().urls(client, names);
        long[] sent = {0};
        new SqsMessageReader().read(files, body -> {
            for (int i = 0; i < names.size(); i++) {
                var request = SendMessageRequest.builder().queueUrl(urls.get(i)).messageBody(body);
                if (group != null) request.messageGroupId(group);
                if (names.get(i).endsWith(".fifo")) request.messageDeduplicationId(UUID.randomUUID().toString());
                client.sendMessage(request.build());
                sent[0]++;
            }
        });
        return sent[0];
    }
}
