package io.netty.ffm;

import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;

/**
 * Shared infrastructure for atomically capturing {@code errno} from native syscalls via
 * {@link Linker.Option#captureCallState(String...)}. Also provides bit-packing utilities
 * to encode a syscall result and errno into a single {@code long} for zero-allocation returns.
 *
 * <p>The packing scheme stores the result (truncated to 32 bits) in the upper half and
 * the errno in the lower half. This is safe for all socket I/O syscalls where individual
 * operations return at most {@code INT_MAX} bytes.
 */
public final class ErrnoState {

    private static final StructLayout CAPTURED_STATE_LAYOUT = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO_HANDLE =
            CAPTURED_STATE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    private ErrnoState() {
    }

    /**
     * Returns the layout required to allocate a captured-state segment. Callers should
     * allocate one segment per event loop thread from a long-lived arena and reuse it
     * across all I/O calls on that thread.
     *
     * @return the {@link StructLayout} for captured errno state
     */
    public static StructLayout layout() {
        return CAPTURED_STATE_LAYOUT;
    }

    /**
     * Reads the errno value from a captured-state segment populated by a downcall handle
     * configured with {@link #captureCallState()}.
     *
     * @param capturedState the segment written to by the most recent downcall
     * @return the captured errno value
     */
    public static int extractErrno(final MemorySegment capturedState) {
        return (int) ERRNO_HANDLE.get(capturedState, 0L);
    }

    /**
     * Returns a {@link Linker.Option} that instructs a downcall handle to atomically
     * capture errno at the moment the native call returns, before the JVM can clobber it.
     *
     * @return the capture-call-state linker option for errno
     */
    public static Linker.Option captureCallState() {
        return Linker.Option.captureCallState("errno");
    }

    /**
     * Bit-packs a syscall result and errno into a single {@code long}. The result
     * occupies the upper 32 bits and errno occupies the lower 32 bits.
     *
     * @param result the syscall return value (e.g. bytes read, fd, or -1 on error)
     * @param errno the errno value captured atomically from the syscall
     * @return a packed {@code long} decodable via {@link #unpackResult} and {@link #unpackErrno}
     */
    public static long pack(final long result, final int errno) {
        return ((result & 0xFFFFFFFFL) << 32) | (errno & 0xFFFFFFFFL);
    }

    /**
     * Extracts the syscall return value from a packed {@code long}.
     *
     * @param packed a value returned by {@link #pack}
     * @return the syscall result as a signed 32-bit integer
     */
    public static int unpackResult(final long packed) {
        return (int) (packed >>> 32);
    }

    /**
     * Extracts the errno value from a packed {@code long}.
     *
     * @param packed a value returned by {@link #pack}
     * @return the errno value
     */
    public static int unpackErrno(final long packed) {
        return (int) packed;
    }
}
