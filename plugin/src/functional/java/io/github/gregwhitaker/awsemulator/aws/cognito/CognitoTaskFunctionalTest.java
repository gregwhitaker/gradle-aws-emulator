package io.github.gregwhitaker.awsemulator.aws.cognito;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.gregwhitaker.awsemulator.testsupport.GradleBuilds.runner;
import static org.junit.jupiter.api.Assertions.*;

class CognitoTaskFunctionalTest {
    @TempDir Path project;

    @ParameterizedTest @ValueSource(strings = {"groovy", "kotlin"})
    void bothDslsConfigureLazilyAndReuseConfigurationCache(String language) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'cognito-fixture'\n");
        boolean kotlin = language.equals("kotlin");
        Files.writeString(project.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), kotlin ? """
            import io.github.gregwhitaker.awsemulator.aws.cognito.*
            import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id("io.github.gregwhitaker.aws-emulator") }
            awsEmulator { dockerExecutable.set("missing-docker-command") }
            val pool = tasks.register<CreateCognitoUserPoolTask>("pool") { userPoolName.set("app") }
            tasks.register<CreateCognitoUserPoolClientTask>("client") { userPoolName.set("app"); clientName.set("web") }
            tasks.register<SeedCognitoUsersTask>("users") { userPoolName.set("app"); fixtures.set(layout.projectDirectory.file("users.json")) }
            tasks.register<ExportCognitoConfigurationTask>("export") {
                userPoolName.set("app"); clientName.set("web"); outputFile.set(layout.buildDirectory.file("cognito.json"))
            }
            check(pool.get().mode.get() == EmulatorMode.MANAGED)
            listOf("listCognitoUserPools", "deleteCognitoUserPool", "deleteCognitoUserPoolClient").forEach {
                check((tasks.named(it).get() as AbstractAwsResourceTask).mode.get() == EmulatorMode.DEVELOPMENT)
            }
            """ : """
            import io.github.gregwhitaker.awsemulator.aws.cognito.*
            import io.github.gregwhitaker.awsemulator.model.EmulatorMode
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('missing-docker-command') }
            def pool = tasks.register('pool', CreateCognitoUserPoolTask) { userPoolName.set('app') }
            tasks.register('client', CreateCognitoUserPoolClientTask) { userPoolName.set('app'); clientName.set('web') }
            tasks.register('users', SeedCognitoUsersTask) { userPoolName.set('app'); fixtures.set(layout.projectDirectory.file('users.json')) }
            tasks.register('export', ExportCognitoConfigurationTask) {
                userPoolName.set('app'); clientName.set('web'); outputFile.set(layout.buildDirectory.file('cognito.json'))
            }
            assert pool.get().mode.get() == EmulatorMode.MANAGED
            ['listCognitoUserPools', 'deleteCognitoUserPool', 'deleteCognitoUserPoolClient'].each {
                assert tasks.named(it).get().mode.get() == EmulatorMode.DEVELOPMENT
            }
            """);
        var output = runner(project, "tasks", "--all").build().getOutput();
        assertTrue(output.contains("AWS Emulator - Cognito tasks"), output);
        assertTrue(runner(project, "tasks", "--all").build().getOutput().contains("Reusing configuration cache"));
        var help = runner(project, "help", "--task", "deleteCognitoUserPoolClient").build().getOutput();
        assertTrue(help.contains("--userPoolName"), help);
        assertTrue(help.contains("--clientName"), help);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }

    @Test void rejectsInvalidInputsBeforeDockerAndDoesNotPrintFixturePasswords() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'cognito-invalid'\n");
        Files.writeString(project.resolve("users.json"), "[{\"username\":\"alice\",\"password\":\"secret-test-value\",\"groups\":[1]}]");
        Files.writeString(project.resolve("build.gradle"), """
            import io.github.gregwhitaker.awsemulator.aws.cognito.*
            plugins { id 'io.github.gregwhitaker.aws-emulator' }
            awsEmulator { dockerExecutable.set('missing-docker-command') }
            tasks.register('pool', CreateCognitoUserPoolTask) { userPoolName.set(' ') }
            tasks.register('client', CreateCognitoUserPoolClientTask) {
                userPoolName.set('app'); clientName.set('web'); explicitAuthFlows.set(['ALLOW_UNSUPPORTED'])
            }
            tasks.register('users', SeedCognitoUsersTask) { userPoolName.set('app'); fixtures.set(layout.projectDirectory.file('users.json')) }
            """);
        String output = runner(project, "pool", "client", "users", "--continue").buildAndFail().getOutput();
        for (String expected : new String[] {"userPoolName must", "explicitAuthFlows must", "Fixture groups must be strings"}) {
            assertTrue(output.contains(expected), output);
        }
        assertFalse(output.contains("secret-test-value"), output);
        assertFalse(Files.exists(project.resolve(".aws-emulator")));
    }
}
