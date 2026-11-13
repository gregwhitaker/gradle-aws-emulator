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

package io.github.gregwhitaker.awsemulator.emulator;

import io.github.gregwhitaker.awsemulator.aws.AbstractAwsResourceTask;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Named;
import org.gradle.api.provider.Provider;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reusable resource and fixture definitions. Each selected runtime gets its own task instances.
 * Configure task inputs here; the attachment selects and locks the runtime name and mode.
 */
public class EmulatorResourceSet implements Named {
    private final String name;
    private final List<Step<?>> provision = new ArrayList<>();
    private final List<Step<?>> seed = new ArrayList<>();
    private final List<Provider<? extends EmulatorResourceSet>> includes = new ArrayList<>();
    private final Set<String> names = new HashSet<>();
    private boolean finalized;

    @Inject public EmulatorResourceSet(String name) {
        validateName(name);
        this.name = name;
    }

    @Override public String getName() { return name; }

    /** Includes other sets as prerequisites. They complete before this set's steps, using the same runtime. */
    @SafeVarargs public final void include(Provider<? extends EmulatorResourceSet>... resources) {
        mutable();
        includes.addAll(Arrays.asList(resources));
    }

    /** Defines a provisioning step. Provisioning runs in declaration order before this set's fixtures. */
    public <T extends AbstractAwsResourceTask> void provision(String name, Class<T> type, Action<? super T> action) {
        add(provision, name, type, action);
    }

    /** Defines a fixture step. Fixtures run in declaration order after provisioning succeeds. */
    public <T extends AbstractAwsResourceTask> void seed(String name, Class<T> type, Action<? super T> action) {
        add(seed, name, type, action);
    }

    private <T extends AbstractAwsResourceTask> void add(List<Step<?>> steps, String name, Class<T> type, Action<? super T> action) {
        mutable();
        validateName(name);
        if (!names.add(name)) throw new GradleException("Duplicate step '" + name + "' in AWS Emulator resource set '" + this.name + "'.");
        steps.add(new Step<>(name, type, action));
    }

    private void mutable() {
        if (finalized) throw new GradleException("AWS Emulator resource set '" + name + "' has already been attached to the task graph.");
    }

    private static void validateName(String name) {
        if (!name.matches("[a-z][a-zA-Z0-9]*(?:-[a-zA-Z0-9]+)*")) {
            throw new GradleException("Resource set and step names must start with a lowercase letter and contain letters, numbers, or single hyphens.");
        }
    }

    List<Provider<? extends EmulatorResourceSet>> includes() { return List.copyOf(includes); }
    List<Step<?>> steps() {
        finalized = true;
        List<Step<?>> result = new ArrayList<>(provision);
        result.addAll(seed);
        return result;
    }

    record Step<T extends AbstractAwsResourceTask>(String name, Class<T> type, Action<? super T> action) {}
}
