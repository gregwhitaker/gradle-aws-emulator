package io.github.gregwhitaker.awsemulator.internal.runtime;

import java.nio.file.Path;

@FunctionalInterface
public interface HealthProbe {
    boolean ready(int port, Path trustStore) throws Exception;
}
