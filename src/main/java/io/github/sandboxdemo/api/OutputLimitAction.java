package io.github.sandboxdemo.api;

/** Behavior when either stream or the combined retained-byte budget is exceeded. */
public enum OutputLimitAction {
    TRUNCATE,
    TERMINATE
}
