package io.github.gregwhitaker.awsemulator.model;

/** Selects the emulator whose resources a provisioning task manages. */
public enum EmulatorMode {
    /** Fresh runtime shared with this project's opted-in tests, removed at build completion. */
    MANAGED,
    /** Persistent runtime controlled by the development lifecycle tasks. */
    DEVELOPMENT
}
