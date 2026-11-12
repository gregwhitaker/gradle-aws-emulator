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

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        try (var context = SpringApplication.run(Application.class, args)) { }
    }

    @Bean(destroyMethod = "close")
    SqsClient sqs() {
        return SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    @Bean
    ApplicationRunner roundTrip(SqsClient sqs) {
        return args -> {
            String url = sqs.getQueueUrl(request -> request.queueName("spring-boot-example-events")).queueUrl();
            sqs.sendMessage(request -> request.queueUrl(url).messageBody("Hello from Spring Boot"));
            var messages = sqs.receiveMessage(request -> request.queueUrl(url).waitTimeSeconds(5)).messages();
            if (messages.isEmpty()) throw new IllegalStateException("Expected an SQS message");
            var message = messages.get(0);
            System.out.println("Received from AWS Emulator: " + message.body());
            sqs.deleteMessage(request -> request.queueUrl(url).receiptHandle(message.receiptHandle()));
            if (args.containsOption("example.fail")) throw new IllegalStateException("Intentional application failure");
        };
    }
}
