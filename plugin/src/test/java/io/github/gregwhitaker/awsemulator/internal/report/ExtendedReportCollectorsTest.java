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

package io.github.gregwhitaker.awsemulator.internal.report;

import io.github.gregwhitaker.awsemulator.aws.ssm.internal.SsmReportCollector;
import io.github.gregwhitaker.awsemulator.aws.secretsmanager.internal.SecretsManagerReportCollector;
import io.github.gregwhitaker.awsemulator.aws.eventbridge.internal.EventBridgeReportCollector;
import io.github.gregwhitaker.awsemulator.aws.kinesis.internal.KinesisReportCollector;
import io.github.gregwhitaker.awsemulator.aws.kms.internal.KmsReportCollector;
import io.github.gregwhitaker.awsemulator.aws.cloudwatchlogs.internal.CloudWatchLogsReportCollector;
import io.github.gregwhitaker.awsemulator.aws.cognito.internal.CognitoReportCollector;
import io.github.gregwhitaker.awsemulator.aws.ses.internal.SesReportCollector;
import io.github.gregwhitaker.awsemulator.testsupport.SdkStub;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.utils.builder.SdkBuilder;
import java.lang.reflect.InvocationHandler;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedReportCollectorsTest {
    @ParameterizedTest @MethodSource("collectors")
    void emptyInventoriesAreComplete(Fixture fixture) {
        var result = fixture.collect().apply((proxy, method, args) -> {
            assertTrue(method.getName().startsWith("list") || method.getName().startsWith("describe"), method.getName());
            // Empty service responses, not failures, must produce a complete empty section.
            return ((SdkBuilder<?, ?>) method.getReturnType().getMethod("builder").invoke(null)).build();
        });
        assertEquals("COMPLETE", result.status());
        assertTrue(result.resources().isEmpty());
        assertTrue(result.issues().isEmpty());
    }

    @ParameterizedTest @MethodSource("collectors")
    void unsupportedInventoriesAreFailedAndDoNotExposeResponseMessages(Fixture fixture) {
        var result = fixture.collect().apply((proxy, method, args) -> {
            throw AwsServiceException.builder().statusCode(501).message("private-service-response").build();
        });
        assertEquals("FAILED", result.status());
        assertTrue(result.resources().isEmpty());
        assertFalse(result.issues().isEmpty());
        assertTrue(result.issues().stream().allMatch(issue -> issue.kind().equals("UNSUPPORTED")));
        assertFalse(result.toString().contains("private-service-response"));
    }

    static Stream<Fixture> collectors() {
        return Stream.of(
            fixture("SSM", SsmClient.class, new SsmReportCollector()::collect),
            fixture("Secrets Manager", SecretsManagerClient.class, new SecretsManagerReportCollector()::collect),
            fixture("EventBridge", EventBridgeClient.class, new EventBridgeReportCollector()::collect),
            fixture("Kinesis", KinesisClient.class, new KinesisReportCollector()::collect),
            fixture("KMS", KmsClient.class, new KmsReportCollector()::collect),
            fixture("CloudWatch Logs", CloudWatchLogsClient.class, new CloudWatchLogsReportCollector()::collect),
            fixture("Cognito", CognitoIdentityProviderClient.class, new CognitoReportCollector()::collect),
            fixture("SES", SesV2Client.class, new SesReportCollector()::collect)
        );
    }

    private static <T> Fixture fixture(String name, Class<T> type, BiConsumer<T, ReportSection> collector) {
        return new Fixture(name, handler -> {
            var section = new ReportSection(name);
            collector.accept(SdkStub.client(type, handler), section);
            return section.result();
        });
    }

    record Fixture(String name, Function<InvocationHandler, ReportSection.Result> collect) {
        @Override public String toString() { return name; }
    }
}
