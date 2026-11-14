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


package io.github.gregwhitaker.awsemulator.aws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class FixtureExportFunctionalTest {
    @TempDir Path project;

    @Test void exportsNeverStartAnOwnedRuntimeOrOverwriteAnExistingArtifactOnConnectionFailure() throws Exception {
        fixture();
        Files.writeString(project.resolve("objects.json"), "previous-objects");
        Files.writeString(project.resolve("items.json"), "previous-items");
        String output = runner(project, "captureObjects", "captureItems", "--continue").buildAndFail().getOutput();
        assertTrue(output.contains("No managed AWS Emulator is active"), output);
        assertFalse(output.contains("Cannot execute"), output);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
        assertEquals("previous-objects", Files.readString(project.resolve("objects.json")));
        assertEquals("previous-items", Files.readString(project.resolve("items.json")));
    }

    @Test void replayValidatesEntireDatasetBeforeConnecting() throws Exception {
        fixture();
        Files.writeString(project.resolve("objects.json"), """
            {"schemaVersion":1,"objects":[{"key":"valid","body":"AA==","metadata":{}},
              {"key":"invalid","body":"private-value","metadata":{}}]}
            """);
        Files.writeString(project.resolve("items.json"), """
            [{"id":{"S":"valid"}},{"id":{"N":"private-value"}}]
            """);
        String output = runner(project, "replayObjects", "replayItems", "--continue").buildAndFail().getOutput();
        assertTrue(output.contains("Invalid S3 fixture"), output);
        assertTrue(output.contains("Invalid DynamoDB fixture"), output);
        assertFalse(output.contains("private-value"), output);
        assertFalse(output.contains("Cannot execute"), output);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    private void fixture() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'fixture-export'\n");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.s3.ExportS3ObjectsTask
            import io.github.gregwhitaker.awsemulator.aws.s3.ReplayS3ObjectsTask
            import io.github.gregwhitaker.awsemulator.aws.dynamodb.ExportDynamoDbItemsTask
            import io.github.gregwhitaker.awsemulator.aws.dynamodb.PutDynamoDbItemsTask
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('no-docker-for-fixture-validation') }
            tasks.register('captureObjects', ExportS3ObjectsTask) {
                bucketName.set('fixture-bucket')
                outputFile.set(layout.projectDirectory.file('objects.json'))
            }
            tasks.register('captureItems', ExportDynamoDbItemsTask) {
                tableName.set('fixture-table')
                outputFile.set(layout.projectDirectory.file('items.json'))
            }
            tasks.register('replayObjects', ReplayS3ObjectsTask) {
                bucketName.set('fixture-bucket')
                objectsFile.set(layout.projectDirectory.file('objects.json'))
            }
            tasks.register('replayItems', PutDynamoDbItemsTask) {
                tableName.set('fixture-table')
                itemsFile.set(layout.projectDirectory.file('items.json'))
            }
            """);
    }
}
