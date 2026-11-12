package io.github.gregwhitaker.awsemulator.boot.internal;

import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.process.CommandLineArgumentProvider;
import java.util.ArrayList;
import java.util.List;

/** Keeps extension values lazy and serializable through Gradle's configuration cache. */
public final class SpringBootJvmArguments implements CommandLineArgumentProvider {
    private final Provider<List<String>> profiles;
    private final Provider<List<String>> jvmArgs;

    public SpringBootJvmArguments(Provider<List<String>> profiles, Provider<List<String>> jvmArgs) {
        this.profiles = profiles;
        this.jvmArgs = jvmArgs;
    }

    @Input public Provider<List<String>> getProfiles() { return profiles; }
    @Input public Provider<List<String>> getJvmArgs() { return jvmArgs; }

    @Override
    public Iterable<String> asArguments() {
        List<String> arguments = new ArrayList<>(jvmArgs.get());
        if (!profiles.get().isEmpty()) arguments.add("-Dspring.profiles.active=" + String.join(",", profiles.get()));
        return arguments;
    }
}
