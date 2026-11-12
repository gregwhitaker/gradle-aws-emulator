package io.github.gregwhitaker.awsemulator.aws.kms;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import io.github.gregwhitaker.awsemulator.aws.internal.*;
import org.gradle.api.GradleException;
import org.gradle.api.provider.*;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import java.util.List;
import java.util.Map;

/** Encrypts a local binary fixture with a symmetric KMS key, writing the result to a separate file. */
@DisableCachingByDefault(because = "Uses live emulator resources")
public abstract class EncryptKmsFileTask extends AbstractAwsResourceTask {
    public EncryptKmsFileTask() { getEncryptionContext().convention(Map.of()); }
    @Input public abstract Property<String> getKeyId();
    @Option(option = "keyId", description = "KeyId") public void setKeyIdOption(String value) { getKeyId().set(value); }
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) public abstract RegularFileProperty getInputFile();
    @OutputFile public abstract RegularFileProperty getOutputFile();
    @Input public abstract MapProperty<String, String> getEncryptionContext();
    @TaskAction
    public void executeTask() {
        String key = ServiceSupport.text(getKeyId().get(), "keyId");
        var output = getOutputFile().get().getAsFile().toPath();
        byte[] input = ResourceFiles.read(getInputFile().get().getAsFile().toPath(), output, 4096);
        try (var client = new AwsClientFactory(connection(), getApiCallTimeoutSeconds().get()).kms()) {
            var response = client.encrypt(software.amazon.awssdk.services.kms.model.EncryptRequest.builder().keyId(key)
                .plaintext(software.amazon.awssdk.core.SdkBytes.fromByteArray(input)).encryptionContext(getEncryptionContext().get()).build());
            ResourceFiles.write(output, response.ciphertextBlob().asByteArray());
        }
    }
}
