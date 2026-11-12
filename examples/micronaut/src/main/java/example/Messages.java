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

package example;

import jakarta.inject.Singleton;
import software.amazon.awssdk.services.sqs.SqsClient;
import java.util.UUID;

/** Micronaut AWS creates and closes the injected SQS client using the normal SDK providers. */
@Singleton
public class Messages {
    private final SqsClient sqs;

    public Messages(SqsClient sqs) { this.sqs = sqs; }

    public String roundTrip() {
        String url = sqs.getQueueUrl(request -> request.queueName("micronaut-example-events")).queueUrl();
        String body = "Hello from Micronaut: " + UUID.randomUUID();
        sqs.sendMessage(request -> request.queueUrl(url).messageBody(body));
        var messages = sqs.receiveMessage(request -> request.queueUrl(url).waitTimeSeconds(5)).messages();
        if (messages.isEmpty()) throw new IllegalStateException("Expected an SQS message");
        var message = messages.get(0);
        sqs.deleteMessage(request -> request.queueUrl(url).receiptHandle(message.receiptHandle()));
        if (!body.equals(message.body())) throw new IllegalStateException("Unexpected SQS message: " + message.body());
        return message.body();
    }
}
