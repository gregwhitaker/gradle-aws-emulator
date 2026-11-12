package io.github.gregwhitaker.awsemulator.internal.runtime;

import org.gradle.api.GradleException;
import org.gradle.process.JavaForkOptions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves consumer trust options immediately before launch, including lazy JVM argument providers. */
public final class ConsumerJvmTrust {
    private static final Set<String> PROPERTIES = Set.of("javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType",
        "javax.net.ssl.trustStorePassword", "javax.net.ssl.trustStoreProvider");

    private ConsumerJvmTrust() {}

    public static Path prepare(JavaForkOptions fork, Path javaHome, EmulatorConnection connection) {
        // Materialize providers only at execution time and only once. Their trust options must not override the merge.
        List<String> provided = new ArrayList<>();
        for (var provider : fork.getJvmArgumentProviders()) provider.asArguments().forEach(provided::add);
        fork.getJvmArgumentProviders().clear();
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, Object> environment = new LinkedHashMap<>(fork.getEnvironment());
        environmentOptions(environment, "JAVA_TOOL_OPTIONS", properties);
        environmentOptions(environment, "JDK_JAVA_OPTIONS", properties);
        // JavaExec keeps jvmArgs separate until its task action, unlike Test's eagerly parsed fork options.
        List<String> arguments = fork.getJvmArgs();
        if (arguments != null) {
            List<String> retained = new ArrayList<>();
            for (String argument : arguments) if (!trustOption(argument, properties)) retained.add(argument);
            fork.setJvmArgs(retained);
        }
        for (String argument : fork.getAllJvmArgs()) trustOption(argument, properties);
        List<String> retainedProviderArguments = new ArrayList<>();
        for (String argument : provided) if (!trustOption(argument, properties)) retainedProviderArguments.add(argument);
        // HotSpot processes _JAVA_OPTIONS after command-line options.
        environmentOptions(environment, "_JAVA_OPTIONS", properties);
        Path store = ConsumerTrustStore.create(connection, javaHome, fork.getWorkingDir().toPath(), properties);
        if (!retainedProviderArguments.isEmpty()) fork.getJvmArgumentProviders().add(() -> retainedProviderArguments);
        fork.setEnvironment(environment);
        return store;
    }

    private static boolean trustOption(String argument, Map<String, String> properties) {
        if (!argument.startsWith("-D")) return false;
        int equals = argument.indexOf('=');
        String name = argument.substring(2, equals < 0 ? argument.length() : equals);
        if (!PROPERTIES.contains(name)) return false;
        properties.put(name, equals < 0 ? "" : argument.substring(equals + 1));
        return true;
    }

    private static void environmentOptions(Map<String, Object> environment, String name, Map<String, String> properties) {
        Object value = environment.get(name);
        if (value == null) return;
        String options = value.toString();
        StringBuilder retained = new StringBuilder();
        for (int index = 0; index < options.length();) {
            int start = index;
            while (index < options.length() && Character.isWhitespace(options.charAt(index))) index++;
            retained.append(options, start, index);
            start = index;
            char quote = 0;
            StringBuilder argument = new StringBuilder();
            while (index < options.length()) {
                char next = options.charAt(index);
                if (quote == 0 && Character.isWhitespace(next)) break;
                index++;
                if (next == quote) quote = 0;
                else if (quote == 0 && (next == '\'' || next == '"')) quote = next;
                else argument.append(next);
            }
            if (quote != 0) throw new GradleException("Unclosed quote in " + name + ". Cannot resolve consumer TLS trust options.");
            if (!trustOption(argument.toString(), properties)) retained.append(options, start, index);
        }
        if (retained.toString().isBlank()) environment.remove(name);
        else environment.put(name, retained.toString());
    }
}
