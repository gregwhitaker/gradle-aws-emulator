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


package io.github.gregwhitaker.awsemulator.model;

/** Selects how a development scenario clears existing state before provisioning and seeding. */
public enum ScenarioResetScope {
    /** Restart the selected runtime, discarding all of its resources. This is the default. */
    RUNTIME,
    /** Run only explicit scenario and resource-set cleanup steps, retaining the runtime and unrelated resources. */
    RESOURCES
}
