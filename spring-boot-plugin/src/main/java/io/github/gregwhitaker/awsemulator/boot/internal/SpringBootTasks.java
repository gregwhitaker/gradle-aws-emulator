package io.github.gregwhitaker.awsemulator.boot.internal;

import io.github.gregwhitaker.awsemulator.boot.SpringBootEmulatorExtension;
import io.github.gregwhitaker.awsemulator.emulator.AwsEmulatorExtension;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;
import org.springframework.boot.gradle.tasks.run.BootRun;

/** Loaded only after Spring Boot is available on the consuming project's plugin classpath. */
public final class SpringBootTasks {
    private SpringBootTasks() {}

    public static void register(Project project, SpringBootEmulatorExtension extension) {
        TaskProvider<BootRun> original = project.getTasks().named("bootRun", BootRun.class);
        register(project, original, extension, "bootRunLocal", false);
        register(project, original, extension, "bootRunLocalDebug", true);
    }

    private static void register(Project project, TaskProvider<BootRun> original,
            SpringBootEmulatorExtension extension, String name, boolean debug) {
        TaskProvider<BootRun> local = project.getTasks().register(name, BootRun.class, task -> {
            BootRun source = original.get();
            task.setGroup("application");
            task.setDescription(debug ? "Runs the Spring Boot application with AWS Emulator and a debugger."
                : "Runs the Spring Boot application with AWS Emulator.");
            task.getMainClass().convention(source.getMainClass());
            task.setClasspath(source.getClasspath());
            task.getJavaLauncher().convention(source.getJavaLauncher());
            task.getOptimizedLaunch().convention(source.getOptimizedLaunch());
            task.setWorkingDir(source.getWorkingDir());
            task.setEnvironment(source.getEnvironment());
            task.setSystemProperties(source.getSystemProperties());
            task.setJvmArgs(source.getJvmArgs());
            task.setArgs(source.getArgs());
            task.getArgumentProviders().addAll(source.getArgumentProviders());
            task.getJvmArgumentProviders().addAll(source.getJvmArgumentProviders());
            task.getJvmArgumentProviders().add(new SpringBootJvmArguments(extension.getProfiles(), extension.getJvmArgs()));
            if (debug) {
                task.getDebugOptions().getEnabled().set(true);
                task.getDebugOptions().getHost().set("127.0.0.1");
                task.getDebugOptions().getPort().set(extension.getDebugPort().map(port -> {
                    if (port < 1 || port > 65535) throw new GradleException("springBootEmulator.debugPort must be between 1 and 65535.");
                    return port;
                }));
                task.getDebugOptions().getServer().set(true);
                task.getDebugOptions().getSuspend().set(extension.getDebugSuspend());
            }
        });
        project.getExtensions().getByType(AwsEmulatorExtension.class)
            .useWith(local, extension.getMode(), extension.getSetupTasks());
    }
}
