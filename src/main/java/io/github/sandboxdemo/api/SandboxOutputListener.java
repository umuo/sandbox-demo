package io.github.sandboxdemo.api;

/** Listener for real-time streaming output emitted by processes executed inside the sandbox. */
public interface SandboxOutputListener {

    /**
     * Invoked when a new chunk of raw standard output is received.
     *
     * @param chunk the raw output bytes (guaranteed not null, defensively created per callback)
     */
    default void onStdout(byte[] chunk) {}

    /**
     * Invoked when a new chunk of raw standard error is received.
     *
     * @param chunk the raw error bytes (guaranteed not null, defensively created per callback)
     */
    default void onStderr(byte[] chunk) {}

    /**
     * Convenience callback invoked when standard output is decoded as a UTF-8 text chunk.
     *
     * @param text the decoded UTF-8 string chunk
     */
    default void onStdoutText(String text) {}

    /**
     * Convenience callback invoked when standard error is decoded as a UTF-8 text chunk.
     *
     * @param text the decoded UTF-8 string chunk
     */
    default void onStderrText(String text) {}

    /** Creates an adapter from functional consumers to a {@link SandboxOutputListener}. */
    static SandboxOutputListener of(
            java.util.function.Consumer<byte[]> stdoutConsumer,
            java.util.function.Consumer<byte[]> stderrConsumer) {
        return new SandboxOutputListener() {
            @Override
            public void onStdout(byte[] chunk) {
                if (stdoutConsumer != null) {
                    stdoutConsumer.accept(chunk);
                }
            }

            @Override
            public void onStderr(byte[] chunk) {
                if (stderrConsumer != null) {
                    stderrConsumer.accept(chunk);
                }
            }
        };
    }
}
