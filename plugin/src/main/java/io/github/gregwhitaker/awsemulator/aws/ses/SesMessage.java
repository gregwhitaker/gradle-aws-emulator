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
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.sesv2.model.*;

import java.util.List;

/** Builds the supported simple/raw requests before a task starts the emulator. */
final class SesMessage {
    private SesMessage() {}

    static SendEmailRequest request(String from, List<String> to, List<String> cc, List<String> bcc,
            List<String> replyTo, String subject, String text, String html, byte[] raw, String configurationSet) {
        SesOperations.address(from, "fromAddress");
        int recipients = to.size() + cc.size() + bcc.size();
        if (recipients < 1 || recipients > 50) throw new GradleException("Supply 1–50 recipients across toAddresses, ccAddresses, and bccAddresses.");
        for (var addresses : List.of(to, cc, bcc, replyTo)) {
            for (String address : addresses) SesOperations.address(address, "Email recipient/reply-to");
        }
        if (configurationSet != null) SesOperations.name(configurationSet, "configurationSetName");
        EmailContent content;
        if (raw != null) {
            if (subject != null || text != null || html != null) {
                throw new GradleException("rawMessage cannot be combined with subject, textBody, or htmlBody.");
            }
            content = EmailContent.builder().raw(RawMessage.builder().data(SdkBytes.fromByteArray(raw)).build()).build();
        } else {
            if (subject == null || subject.isBlank() || subject.chars().anyMatch(Character::isISOControl)) {
                throw new GradleException("subject must be nonblank and contain no control characters.");
            }
            SesOperations.body(text, html);
            var body = Body.builder();
            if (text != null) body.text(utf8(text));
            if (html != null) body.html(utf8(html));
            content = EmailContent.builder().simple(Message.builder().subject(utf8(subject)).body(body.build()).build()).build();
        }
        return SendEmailRequest.builder().fromEmailAddress(from)
            .destination(Destination.builder().toAddresses(to).ccAddresses(cc).bccAddresses(bcc).build())
            .replyToAddresses(replyTo).content(content).configurationSetName(configurationSet).build();
    }

    private static Content utf8(String text) { return Content.builder().data(text).charset("UTF-8").build(); }
}
