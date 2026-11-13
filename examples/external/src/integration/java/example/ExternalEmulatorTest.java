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

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import static org.junit.jupiter.api.Assertions.*;

class ExternalEmulatorTest {
    @Test
    void connectsWithTheSuppliedCredentialsAndTrust() {
        try (var sqs = SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            String url = sqs.getQueueUrl(request -> request.queueName("external-example-events")).queueUrl();
            assertTrue(url.contains("external-example-events"));
            assertFalse(Boolean.getBoolean("example.fail"), "Intentional external consumer failure");
        }
    }
}
