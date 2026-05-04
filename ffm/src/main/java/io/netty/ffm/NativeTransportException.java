package io.netty.ffm;

/**
 * Thrown when a native FFM downcall handle invocation fails unexpectedly. This typically
 * indicates a method handle type mismatch or a catastrophic JVM-level error, not a
 * normal syscall failure (which is reported via errno in the packed return value).
 */
public final class NativeTransportException extends RuntimeException {

    private final String syscall;

    /**
     * Creates a new exception for a failed native syscall invocation.
     *
     * @param syscall the name of the syscall that failed (e.g. "read", "kevent")
     * @param cause the underlying throwable from the method handle invocation
     */
    public NativeTransportException(final String syscall, final Throwable cause) {
        super(syscall + "() invocation failed", cause);
        this.syscall = syscall;
    }

    /**
     * Returns the name of the native syscall whose invocation failed.
     *
     * @return the syscall name
     */
    public String syscall() {
        return syscall;
    }
}
