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

/**
 * Selects the emulator whose resources a provisioning task manages.
 * When an external connection is enabled, both modes use that endpoint without managing its lifecycle.
 */
public enum EmulatorMode {
    /** Fresh runtime shared with this project's opted-in tests, removed at build completion. */
    MANAGED,
    /** Persistent runtime controlled by the development lifecycle tasks. */
    DEVELOPMENT
}
