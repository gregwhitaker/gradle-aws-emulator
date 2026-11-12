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

package io.github.gregwhitaker.awsemulator.aws.dynamodb;

import org.gradle.api.GradleException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Resolves a build-classpath initializer before provisioning and invokes it only after table readiness. */
final class DynamoDbInitializerExecutor {
    private final Constructor<?> constructor;
    private final Method run;

    private DynamoDbInitializerExecutor(Constructor<?> constructor, Method run) {
        this.constructor = constructor;
        this.run = run;
    }

    static DynamoDbInitializerExecutor resolve(String className, ClassLoader loader) {
        if (className.isBlank()) throw new GradleException("initializer must be a fully qualified class name.");
        Class<?> type;
        try {
            type = Class.forName(className, false, loader);
        } catch (ClassNotFoundException missing) {
            throw new GradleException("DynamoDB initializer class not found on the build classpath: " + className, missing);
        }
        try {
            Constructor<?> constructor = type.getConstructor(DynamoDbClient.class);
            Method run = type.getMethod("run");
            if (!Modifier.isPublic(type.getModifiers()) || Modifier.isAbstract(type.getModifiers())
                    || Modifier.isStatic(run.getModifiers()) || run.getReturnType() != void.class) {
                throw new NoSuchMethodException("Initializer must be a public concrete class with a public instance void run() method.");
            }
            return new DynamoDbInitializerExecutor(constructor, run);
        } catch (NoSuchMethodException invalid) {
            throw new GradleException("DynamoDB initializer " + className
                + " requires a public constructor accepting DynamoDbClient and a public instance void run() method with no arguments.", invalid);
        }
    }

    void invoke(DynamoDbClient client) {
        try {
            run.invoke(constructor.newInstance(client));
        } catch (InvocationTargetException failure) {
            throw new GradleException("DynamoDB initializer failed: " + constructor.getDeclaringClass().getName(), failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new GradleException("Cannot invoke DynamoDB initializer: " + constructor.getDeclaringClass().getName(), failure);
        }
    }
}
