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

package io.github.gregwhitaker.awsemulator.internal;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.provider.ProviderInternal;
import org.gradle.api.provider.Provider;
import java.util.function.Consumer;

/** Visits declared local setup dependencies without asking Gradle to resolve another project's task graph. */
public final class LocalTaskDependencies {
    private LocalTaskDependencies() {}

    public static void visitPrerequisites(Project project, Task task, Consumer<Task> visitor) {
        visit(project, task.getDependsOn(), visitor);
        visitProducers(project, task.getInputs().getFiles().getElements(), visitor);
    }

    public static void visit(Project project, Object notation, Consumer<Task> visitor) {
        if (notation instanceof Task task) {
            if (task.getProject() == project) visitor.accept(task);
        } else if (notation instanceof CharSequence path) {
            String value = path.toString();
            int separator = value.lastIndexOf(':');
            if (separator >= 0) {
                String owner = separator == 0 ? ":" : value.substring(0, separator);
                if (!owner.equals(project.getPath())) return;
                value = value.substring(separator + 1);
            }
            visitor.accept(project.getTasks().getByName(value));
        } else if (notation instanceof Provider<?> provider) {
            if (!visitProducers(project, provider, visitor)) visit(project, provider.getOrNull(), visitor);
        } else if (notation instanceof FileCollection files) {
            visitProducers(project, files.getElements(), visitor);
        } else if (notation instanceof Iterable<?> values) {
            for (Object value : values) visit(project, value, visitor);
        } else if (notation instanceof Object[] values) {
            for (Object value : values) visit(project, value, visitor);
        }
    }

    /** Visits producer metadata without evaluating a value that may depend on an unfinished task. */
    public static boolean visitProducers(Project project, Provider<?> provider, Consumer<Task> visitor) {
        // Gradle exposes producer metadata only through ProviderInternal. Keep this compatibility bridge
        // here and cover it on supported Gradle versions; resolving TaskDependency is forbidden with
        // Isolated Projects, while get() can read task output before its producer has executed.
        if (!(provider instanceof ProviderInternal<?> internal)) return false;
        boolean[] produced = {false};
        internal.getProducer().visitProducerTasks(task -> {
            produced[0] = true;
            if (task.getProject() == project) visitor.accept(task);
        });
        return produced[0];
    }
}
