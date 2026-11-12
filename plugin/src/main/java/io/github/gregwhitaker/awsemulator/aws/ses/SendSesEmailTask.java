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

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.AwsClientFactory;
import io.github.gregwhitaker.awsemulator.aws.internal.ResourceFiles;
import io.github.gregwhitaker.awsemulator.aws.internal.ServiceSupport;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import software.amazon.awssdk.core.exception.SdkException;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Submits simple or raw email to local SES and writes its message ID. This task does not relay SMTP. */
@DisableCachingByDefault(because = "Sends a new message to the live emulator on every invocation")
public abstract class SendSesEmailTask extends AbstractAwsResourceTask {
    public SendSesEmailTask() {
        getToAddresses().convention(List.of());
        getCcAddresses().convention(List.of());
        getBccAddresses().convention(List.of());
        getReplyToAddresses().convention(List.of());
    }

    /** Verified bare sender address, required even when raw MIME contains a From header. */
    @Input public abstract Property<String> getFromAddress();
    /** To recipients; the combined To/Cc/Bcc list must contain 1–50 bare addresses. */
    @Input public abstract ListProperty<String> getToAddresses();
    /** Cc recipients. */
    @Input public abstract ListProperty<String> getCcAddresses();
    /** Bcc recipients. */
    @Input public abstract ListProperty<String> getBccAddresses();
    /** Optional reply-to addresses. */
    @Input public abstract ListProperty<String> getReplyToAddresses();
    /** Simple-message subject; incompatible with rawMessage. */
    @Input @Optional public abstract Property<String> getSubject();
    /** UTF-8 plain text body; incompatible with rawMessage. */
    @Input @Optional public abstract Property<String> getTextBody();
    /** UTF-8 HTML body; incompatible with rawMessage. */
    @Input @Optional public abstract Property<String> getHtmlBody();
    /** Complete MIME document, up to 40 MiB. Supply envelope sender and recipients separately. */
    @InputFile @Optional @PathSensitive(PathSensitivity.RELATIVE) public abstract RegularFileProperty getRawMessage();
    /** Existing configuration set used for event publication. */
    @Input @Optional public abstract Property<String> getConfigurationSetName();
    /** UTF-8 message ID output; defaults to build/aws-emulator/ses/TASK/message-id.txt. */
    @OutputFile public abstract RegularFileProperty getMessageIdFile();

    @Option(option = "fromAddress", description = "Verified bare sender address")
    public void setFromAddressOption(String value) { getFromAddress().set(value); }
    @Option(option = "toAddresses", description = "Comma-separated bare recipient addresses")
    public void setToAddressesOption(String value) { getToAddresses().set(List.of(value.split(",", -1))); }
    @Option(option = "subject", description = "Simple email subject")
    public void setSubjectOption(String value) { getSubject().set(value); }
    @Option(option = "textBody", description = "Simple email plain text body")
    public void setTextBodyOption(String value) { getTextBody().set(value); }
    @Option(option = "configurationSetName", description = "Existing SES configuration set")
    public void setConfigurationSetNameOption(String value) { getConfigurationSetName().set(value); }

    @TaskAction public void send() {
        var output = getMessageIdFile().get().getAsFile().toPath();
        byte[] raw = getRawMessage().isPresent()
            ? ResourceFiles.read(getRawMessage().get().getAsFile().toPath(), output, 40 * 1024 * 1024) : null;
        var request = SesMessage.request(getFromAddress().get(), getToAddresses().get(), getCcAddresses().get(),
            getBccAddresses().get(), getReplyToAddresses().get(), getSubject().getOrNull(), getTextBody().getOrNull(),
            getHtmlBody().getOrNull(), raw, getConfigurationSetName().getOrNull());
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).sesV2()) {
            String id = ServiceSupport.text(client.sendEmail(request).messageId(), "SES message ID");
            ResourceFiles.write(output, (id + "\n").getBytes(StandardCharsets.UTF_8));
            getLogger().lifecycle("SES accepted message {} (no external email delivery).", id);
        } catch (SdkException error) { throw SesOperations.failure("Sending SES email", error); }
    }
}
