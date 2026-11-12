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
