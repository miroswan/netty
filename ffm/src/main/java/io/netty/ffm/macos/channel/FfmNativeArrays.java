package io.netty.ffm.macos.channel;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.posix.IovArray;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Per-event-loop native resource bundle allocated from the handler's confined arena.
 * Provides reusable off-heap structures for I/O syscalls, avoiding per-call allocation.
 *
 * <p>Channels access this via {@code registration.attachment()} and use:
 * <ul>
 *   <li>{@link #capturedState()} — for errno capture on every syscall</li>
 *   <li>{@link #cleanIovArray()} — for vectored writes (writev)</li>
 *   <li>{@link #sendfileLenBuf()} — for macOS sendfile's off_t* len parameter</li>
 * </ul>
 *
 * <p>Since all channels on a given event loop share the same thread and only one
 * channel's I/O executes at any time, these resources are safely reused without
 * synchronization.
 */
public final class FfmNativeArrays {

    private static final int IOV_MAX = 1024;

    private final IovArray iovArray;
    private final MemorySegment capturedState;
    private final MemorySegment sendfileLenBuf;
    private final FfmIovArrayAdapter iovAdapter;

    /**
     * Creates a new resource bundle from the given arena.
     *
     * @param arena the confined arena that owns all backing memory
     */
    public FfmNativeArrays(final Arena arena) {
        this.iovArray = new IovArray(arena, IOV_MAX);
        this.capturedState = arena.allocate(ErrnoState.layout());
        this.sendfileLenBuf = arena.allocate(ValueLayout.JAVA_LONG);
        this.iovAdapter = new FfmIovArrayAdapter();
    }

    /**
     * Returns the pre-allocated captured state segment for errno-safe syscalls.
     *
     * @return the capturedState segment
     */
    public MemorySegment capturedState() {
        return capturedState;
    }

    /**
     * Returns a cleared {@link IovArray} ready for populating with iovec entries.
     *
     * @return the reusable iov array, reset to empty
     */
    public IovArray cleanIovArray() {
        iovArray.clear();
        return iovArray;
    }

    /**
     * Returns the pre-allocated 8-byte buffer used as the {@code off_t *len}
     * parameter for macOS {@code sendfile(2)}. The caller writes the desired
     * count before the call and reads back the actual bytes sent after.
     *
     * @return the sendfile length buffer
     */
    public MemorySegment sendfileLenBuf() {
        return sendfileLenBuf;
    }

    /**
     * Returns the reusable {@link FfmIovArrayAdapter} for populating the IovArray
     * from a {@link io.netty.channel.ChannelOutboundBuffer}.
     *
     * @return the shared adapter instance
     */
    public FfmIovArrayAdapter iovAdapter() {
        return iovAdapter;
    }
}
