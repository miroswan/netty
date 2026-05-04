package io.netty.ffm.kqueue;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.NativeTransportException;
import io.netty.ffm.unix.generated.Errno;
import io.netty.ffm.kqueue.generated.Event;
import io.netty.ffm.kqueue.generated.kevent;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Zero-allocation kqueue I/O wrappers with atomic errno capture. Provides an errno-safe
 * {@link #kevent} downcall, cached struct offsets for direct field access in the event
 * dispatch loop, and an {@link #evSet} helper to populate {@code struct kevent} entries.
 *
 * <p>This class requires a 64-bit architecture. The {@code udata} field is written as a
 * raw {@code long} to avoid allocating {@link MemorySegment} wrappers for pointers.
 *
 * <p>The caller must pre-allocate a {@code capturedState} segment from
 * {@link ErrnoState#layout()} on a long-lived arena and pass it to {@link #kevent}.
 */
public final class KqueueIO {

    static {
        if (ValueLayout.ADDRESS.byteSize() != 8) {
            throw new ExceptionInInitializerError("KqueueIO requires a 64-bit architecture");
        }
    }

    private static final Linker LINKER = Linker.nativeLinker();

    private static final MethodHandle KEVENT_HANDLE = LINKER.downcallHandle(
            LINKER.defaultLookup().findOrThrow("kevent"),
            FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS),
            ErrnoState.captureCallState());

    /** Size in bytes of a single {@code struct kevent}. */
    public static final long KEVENT_SIZE = kevent.sizeof();
    /** Byte offset of the {@code ident} field within {@code struct kevent}. */
    public static final long IDENT_OFFSET = kevent.ident$offset();
    /** Byte offset of the {@code filter} field within {@code struct kevent}. */
    public static final long FILTER_OFFSET = kevent.filter$offset();
    /** Byte offset of the {@code flags} field within {@code struct kevent}. */
    public static final long FLAGS_OFFSET = kevent.flags$offset();
    /** Byte offset of the {@code fflags} field within {@code struct kevent}. */
    public static final long FFLAGS_OFFSET = kevent.fflags$offset();
    /** Byte offset of the {@code data} field within {@code struct kevent}. */
    public static final long DATA_OFFSET = kevent.data$offset();
    /** Byte offset of the {@code udata} field within {@code struct kevent}. */
    public static final long UDATA_OFFSET = kevent.udata$offset();

    /** Filter: read events. */
    public static final short EVFILT_READ = (short) Event.EVFILT_READ();
    /** Filter: write events. */
    public static final short EVFILT_WRITE = (short) Event.EVFILT_WRITE();
    /** Filter: timer events. */
    public static final short EVFILT_TIMER = (short) Event.EVFILT_TIMER();
    /** Filter: user-defined events. */
    public static final short EVFILT_USER = (short) Event.EVFILT_USER();
    /** Action: add event to kqueue (implies enable). */
    public static final short EV_ADD = (short) Event.EV_ADD();
    /** Action: delete event from kqueue. */
    public static final short EV_DELETE = (short) Event.EV_DELETE();
    /** Action: enable event. */
    public static final short EV_ENABLE = (short) Event.EV_ENABLE();
    /** Action: disable event (not reported). */
    public static final short EV_DISABLE = (short) Event.EV_DISABLE();
    /** Flag: clear event state after reporting. */
    public static final short EV_CLEAR = (short) Event.EV_CLEAR();
    /** Flag: EOF detected on the descriptor. */
    public static final short EV_EOF = (short) Event.EV_EOF();
    /** Flag: error occurred, data contains errno. */
    public static final short EV_ERROR = (short) Event.EV_ERROR();
    /** Flag: only report one occurrence then delete. */
    public static final short EV_ONESHOT = (short) Event.EV_ONESHOT();
    /** User event fflags: trigger the event for output. */
    public static final int NOTE_TRIGGER = Event.NOTE_TRIGGER();

    private KqueueIO() {
    }

    /**
     * Returns {@code true} if the packed kevent result indicates the call was interrupted
     * by a signal ({@code EINTR}) and should be retried.
     *
     * @param packed a bit-packed result from {@link #kevent}
     * @return {@code true} if the call should be retried immediately
     */
    public static boolean isInterrupted(final long packed) {
        return ErrnoState.unpackResult(packed) < 0
                && ErrnoState.unpackErrno(packed) == Errno.EINTR();
    }

    /**
     * Returns {@code true} if the packed kevent result indicates a non-retriable error.
     *
     * @param packed a bit-packed result from {@link #kevent}
     * @return {@code true} if the call failed with a fatal error
     */
    public static boolean isError(final long packed) {
        return ErrnoState.unpackResult(packed) < 0
                && ErrnoState.unpackErrno(packed) != Errno.EINTR();
    }

    /**
     * Creates a new kqueue file descriptor.
     *
     * @return the kqueue fd, or -1 on error
     */
    public static int kqueue() {
        return Event.kqueue();
    }

    /**
     * Registers events with and/or retrieves pending events from a kqueue, with
     * atomic errno capture.
     *
     * @param kq the kqueue file descriptor
     * @param changelist segment containing events to register (may be {@code NULL})
     * @param nchanges the number of events in the changelist
     * @param eventlist segment to receive ready events (may be {@code NULL})
     * @param nevents the maximum number of events to return
     * @param timeout timeout specification (may be {@code NULL} for infinite wait)
     * @param capturedState pre-allocated segment sized to {@link ErrnoState#layout()}
     * @return a bit-packed {@code long} with ready count (upper 32) and errno (lower 32)
     * @throws NativeTransportException if the downcall handle invocation fails
     */
    public static long kevent(final int kq, final MemorySegment changelist,
                              final int nchanges, final MemorySegment eventlist,
                              final int nevents, final MemorySegment timeout,
                              final MemorySegment capturedState) {
        try {
            final int ready = (int) KEVENT_HANDLE.invokeExact(
                    capturedState, kq, changelist, nchanges, eventlist, nevents, timeout);
            return ErrnoState.pack(ready, ErrnoState.extractErrno(capturedState));
        } catch (final Throwable t) {
            throw new NativeTransportException("kevent", t);
        }
    }

    /**
     * Populates a {@code struct kevent} entry in-place. Equivalent to the BSD
     * {@code EV_SET} macro.
     *
     * @param kev a segment pointing to a single {@code struct kevent}
     * @param ident the event identifier (typically a file descriptor)
     * @param filter the event filter (e.g. {@link #EVFILT_READ}, {@link #EVFILT_WRITE})
     * @param flags the event action flags (e.g. {@link #EV_ADD}, {@link #EV_DELETE})
     * @param fflags filter-specific flags
     * @param data filter-specific data
     * @param udata opaque user data, stored as a raw 64-bit value
     */
    public static void evSet(final MemorySegment kev, final long ident, final short filter,
                             final short flags, final int fflags, final long data, final long udata) {
        kevent.ident(kev, ident);
        kevent.filter(kev, filter);
        kevent.flags(kev, flags);
        kevent.fflags(kev, fflags);
        kevent.data(kev, data);
        kev.set(ValueLayout.JAVA_LONG, UDATA_OFFSET, udata);
    }

    /**
     * Reads the {@code ident} field from the kevent at the given index in an event array.
     *
     * @param eventArray the contiguous kevent array segment
     * @param index the zero-based event index
     * @return the event identifier
     */
    public static long ident(final MemorySegment eventArray, final int index) {
        return eventArray.get(kevent.ident$layout(), index * KEVENT_SIZE + IDENT_OFFSET);
    }

    /**
     * Reads the {@code filter} field from the kevent at the given index in an event array.
     *
     * @param eventArray the contiguous kevent array segment
     * @param index the zero-based event index
     * @return the event filter
     */
    public static short filter(final MemorySegment eventArray, final int index) {
        return eventArray.get(kevent.filter$layout(), index * KEVENT_SIZE + FILTER_OFFSET);
    }

    /**
     * Reads the {@code flags} field from the kevent at the given index in an event array.
     *
     * @param eventArray the contiguous kevent array segment
     * @param index the zero-based event index
     * @return the event flags
     */
    public static short flags(final MemorySegment eventArray, final int index) {
        return eventArray.get(kevent.flags$layout(), index * KEVENT_SIZE + FLAGS_OFFSET);
    }

    /**
     * Reads the {@code fflags} field from the kevent at the given index in an event array.
     *
     * @param eventArray the contiguous kevent array segment
     * @param index the zero-based event index
     * @return the filter-specific flags
     */
    public static int fflags(final MemorySegment eventArray, final int index) {
        return eventArray.get(kevent.fflags$layout(), index * KEVENT_SIZE + FFLAGS_OFFSET);
    }

    /**
     * Reads the {@code data} field from the kevent at the given index in an event array.
     *
     * @param eventArray the contiguous kevent array segment
     * @param index the zero-based event index
     * @return the filter-specific data
     */
    public static long data(final MemorySegment eventArray, final int index) {
        return eventArray.get(kevent.data$layout(), index * KEVENT_SIZE + DATA_OFFSET);
    }
}
