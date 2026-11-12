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

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import org.springframework.stereotype.Component;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
public class EventListener {
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

    @SqsListener(value = "${example.queue}", acknowledgementMode = "MANUAL")
    public void receive(String message, Acknowledgement acknowledgement) throws Exception {
        acknowledgement.acknowledgeAsync().get(30, TimeUnit.SECONDS);
        received.add(message);
    }

    public void await(String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            String message = received.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (expected.equals(message)) return;
        }
        throw new AssertionError("Listener did not acknowledge the expected message within 30 seconds");
    }
}
