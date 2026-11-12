package io.github.gregwhitaker.awsemulator.aws.kinesis;

import org.gradle.work.DisableCachingByDefault;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class KinesisTaskCachingTest {
    @ParameterizedTest
    @ValueSource(classes = {AbstractKinesisTask.class, CreateKinesisStreamTask.class,
        DeleteKinesisStreamTask.class, ListKinesisStreamsTask.class, PublishKinesisRecordsTask.class})
    void declaresWhyLiveResourceTasksCannotBeCached(Class<?> taskType) {
        // Gradle validates abstract task types too, and the caching declaration is not inherited.
        var policy = taskType.getDeclaredAnnotation(DisableCachingByDefault.class);
        assertNotNull(policy, taskType.getSimpleName() + " must declare its caching policy");
        assertFalse(policy.because().isBlank(), "The policy must explain why the task cannot be cached");
    }
}
