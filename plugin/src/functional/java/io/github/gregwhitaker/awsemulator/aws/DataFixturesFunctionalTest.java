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

class DataFixturesFunctionalTest {
    @TempDir Path project;

    @Test void configuresDataTasksLazilyAndRejectsInvalidInputsWithoutDocker() throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"data-fixtures\"\n");
        Files.createDirectory(project.resolve("assets"));
        Files.writeString(project.resolve("assets/file.txt"), "example");
        Files.writeString(project.resolve("items.json"), "[{\"id\":{\"N\":\"private-value\"}}]");
        Files.writeString(project.resolve("build.gradle.kts"), """
            import io.github.gregwhitaker.awsemulator.aws.s3.*
            import io.github.gregwhitaker.awsemulator.aws.dynamodb.*
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("this-command-does-not-exist") }
            tasks.register<UploadS3ObjectTask>("uploadOne") {
                bucketName.set("fixture-bucket"); objectKey.set(""); sourceFile.set(layout.projectDirectory.file("assets/file.txt"))
            }
            tasks.register<UploadS3DirectoryTask>("uploadDirectory") {
                bucketName.set("fixture-bucket"); sourceDirectory.set(layout.projectDirectory.dir("assets")); prefix.set("é".repeat(513))
            }
            tasks.register<DownloadS3ObjectTask>("download") {
                bucketName.set("fixture-bucket"); objectKey.set("file"); outputFile.set(layout.buildDirectory.file("file.txt"))
            }
            tasks.register<PutDynamoDbItemsTask>("seedData") {
                tableName.set("fixture-table"); itemsFile.set(layout.projectDirectory.file("items.json"))
            }
            """);
        String tasks = runner(project, "tasks", "--all").build().getOutput();
        assertTrue(tasks.contains("listS3Objects"), tasks);
        assertTrue(tasks.contains("purgeDynamoDbTable"), tasks);
        String invalidItems = runner(project, "seedData").buildAndFail().getOutput();
        assertTrue(invalidItems.contains("Invalid DynamoDB fixture"), invalidItems);
        assertFalse(invalidItems.contains("private-value"), invalidItems);
        assertTrue(runner(project, "uploadOne").buildAndFail().getOutput().contains("objectKey must"));
        assertTrue(runner(project, "uploadDirectory").buildAndFail().getOutput().contains("objectKey must"));
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }
}
