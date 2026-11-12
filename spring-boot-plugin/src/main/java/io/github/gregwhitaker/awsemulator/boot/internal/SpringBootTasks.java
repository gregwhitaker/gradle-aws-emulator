package io.github.gregwhitaker.awsemulator.boot.internal;

import io.github.gregwhitaker.awsemulator.boot.SpringBootEmulatorExtension;
import io.github.gregwhitaker.awsemulator.emulator.AwsEmulatorExtension;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskProvider;

/** Loaded only after Spring Boot is available on the consuming project's plugin classpath. */
public final class SpringBootTasks {
    private SpringBootTasks() {}

    public static void register(Project project, SpringBootEmulatorExtension extension) {
        // Boot can live in a child classloader (TestKit or a parent-applied convention plugin).
        // Resolve the application's actual BootRun type without imposing a Boot dependency/version.
        Class<? extends JavaExec> type;
        try {
            type = Class.forName("org.springframework.boot.gradle.tasks.run.BootRun", false,
                project.getPlugins().getPlugin("org.springframework.boot").getClass().getClassLoader()).asSubclass(JavaExec.class);
        } catch (ClassNotFoundException | ClassCastException error) {
            throw new GradleException("Cannot locate Spring Boot's BootRun task type. Use a supported Spring Boot plugin version.", error);
        }
        TaskProvider<? extends JavaExec> original = project.getTasks().named("bootRun", type);
        register(project, type, original, extension, "bootRunLocal", false);
        register(project, type, original, extension, "bootRunLocalDebug", true);
    }

    private static void register(Project project, Class<? extends JavaExec> type, TaskProvider<? extends JavaExec> original,
            SpringBootEmulatorExtension extension, String name, boolean debug) {
        TaskProvider<? extends JavaExec> local = project.getTasks().register(name, type, task -> {
            JavaExec source = original.get();
            task.setGroup("application");
            task.setDescription(debug ? "Runs the Spring Boot application with AWS Emulator and a debugger."
                : "Runs the Spring Boot application with AWS Emulator.");
            task.getMainClass().convention(source.getMainClass());
            task.setClasspath(source.getClasspath());
            task.getJavaLauncher().convention(source.getJavaLauncher());
            optimizedLaunch(task).convention(optimizedLaunch(source));
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

    @SuppressWarnings("unchecked")
    private static Property<Boolean> optimizedLaunch(JavaExec task) {
        try {
            return (Property<Boolean>) task.getClass().getMethod("getOptimizedLaunch").invoke(task);
        } catch (ReflectiveOperationException | ClassCastException error) {
            throw new GradleException("Spring Boot's BootRun task must expose the optimizedLaunch property.", error);
        }
    }
}
