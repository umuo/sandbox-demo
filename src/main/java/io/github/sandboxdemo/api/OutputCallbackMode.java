package io.github.sandboxdemo.api;

/** Callbacks observe raw bytes or only the retained prefix; neither is governed final delivery. */
public enum OutputCallbackMode {
    RAW,
    RETAINED
}
