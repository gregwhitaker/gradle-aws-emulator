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

package io.github.gregwhitaker.awsemulator.aws.ses;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SesMessageTest {
    @Test void preservesMultipartContentAndEnvelopeRecipients() {
        var request = SesMessage.request("sender@example.test", List.of("to@example.test"), List.of("cc@example.test"),
            List.of("bcc@example.test"), List.of("reply@example.test"), "Hello café", "Text café", "<p>café</p>", null, "mail");
        assertEquals("Text café", request.content().simple().body().text().data());
        assertEquals("<p>café</p>", request.content().simple().body().html().data());
        assertEquals("UTF-8", request.content().simple().subject().charset());
        assertEquals(List.of("bcc@example.test"), request.destination().bccAddresses());
        assertEquals(List.of("reply@example.test"), request.replyToAddresses());
        assertEquals("mail", request.configurationSetName());
    }

    @Test void rawMimeIsUnchangedAndRequiresExplicitEnvelope() {
        byte[] mime = "From: sender@example.test\r\nSubject: Raw\r\n\r\nBody\r\n".getBytes(StandardCharsets.UTF_8);
        var request = SesMessage.request("sender@example.test", List.of("to@example.test"), List.of(), List.of(), List.of(),
            null, null, null, mime, null);
        assertArrayEquals(mime, request.content().raw().data().asByteArray());
        assertThrows(GradleException.class, () -> SesMessage.request("sender@example.test", List.of(), List.of(), List.of(), List.of(),
            null, null, null, mime, null));
        assertThrows(GradleException.class, () -> SesMessage.request("sender@example.test", List.of("to@example.test"), List.of(), List.of(), List.of(),
            "Subject", null, null, mime, null));
    }

    @Test void rejectsInvalidRequestsBeforeConnecting() {
        for (String subject : new String[] {null, "", "Subject\r\nBcc: injected@example.test"}) {
            assertThrows(GradleException.class, () -> SesMessage.request("sender@example.test", List.of("to@example.test"), List.of(), List.of(), List.of(),
                subject, "Body", null, null, null));
        }
        assertThrows(GradleException.class, () -> SesMessage.request("sender@example.test", Collections.nCopies(51, "to@example.test"),
            List.of(), List.of(), List.of(), "Subject", "Body", null, null, null));
        assertThrows(GradleException.class, () -> SesOperations.content("Subject", null, ""));
        assertThrows(GradleException.class, () -> SesOperations.content("Subject", "x".repeat(500_000), null));
        assertThrows(GradleException.class, () -> SesOperations.identity("example..test"));
        assertThrows(GradleException.class, () -> SesOperations.address("Sender <sender@example.test>", "fromAddress"));
        assertThrows(GradleException.class, () -> SesOperations.eventTypes(List.of("SEND", "SEND")));
        assertThrows(GradleException.class, () -> SesOperations.eventTypes(List.of("made-up")));
    }
}
