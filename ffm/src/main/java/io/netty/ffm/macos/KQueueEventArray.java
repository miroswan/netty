package io.netty.ffm.macos;

import io.netty.ffm.macos.generated.kevent;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Arena-backed growable array of {@code struct kevent} entries. Used as both the changelist
 * (events to register) and the eventlist (ready events to receive) for
 * {@link KqueueIO#kevent}.
 *
 * <p>The caller provides the {@link Arena} that owns the backing memory. For event loop
 * usage, pass a confined arena owned by the event loop thread to avoid the synchronization
 * overhead of shared arenas. Call {@link #close()} to release all memory, or let the
 * owning arena's lifecycle manage it.
 *
 * <p>For the changelist pattern, call {@link #clear()} before each batch,
 * {@link #add} to populate entries, then pass {@link #memory()} and {@link #size()}
 * to {@link KqueueIO#kevent}.
 *
 * <p>For the eventlist pattern, allocate once at the desired max capacity, pass
 * {@link #memory()} and {@link #capacity()} to {@link KqueueIO#kevent}, then iterate
 * over the returned ready count using the indexed accessors.
 */
public final class KQueueEventArray {

    private final Arena arena;
    private MemorySegment memory;
    private int capacity;
    private int size;

    /**
     * Creates a new event array backed by the given arena.
     *
     * @param arena the arena that owns the backing memory
     * @param initialCapacity the number of kevent slots to pre-allocate
     */
    public KQueueEventArray(final Arena arena, final int initialCapacity) {
        this.arena = arena;
        this.capacity = initialCapacity;
        this.memory = kevent.allocateArray(initialCapacity, arena);
        this.size = 0;
    }

    /**
     * Returns the backing memory segment containing all kevent entries.
     *
     * @return the contiguous kevent array segment
     */
    public MemorySegment memory() {
        return memory;
    }

    /**
     * Returns the number of kevent entries currently in the array.
     *
     * @return the current size
     */
    public int size() {
        return size;
    }

    /**
     * Returns the total number of kevent slots allocated.
     *
     * @return the current capacity
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Resets the size to zero without deallocating memory. Use before populating
     * a new batch of changelist entries.
     */
    public void clear() {
        size = 0;
    }

    /**
     * Adds a kevent entry to the array, growing the backing memory if needed.
     *
     * @param ident the event identifier (typically a file descriptor)
     * @param filter the event filter (e.g. {@link KqueueIO#EVFILT_READ})
     * @param flags the event action flags (e.g. {@link KqueueIO#EV_ADD})
     * @param fflags filter-specific flags
     * @param data filter-specific data
     * @param udata opaque user data stored as a raw 64-bit value
     */
    public void add(final long ident, final short filter, final short flags,
                    final int fflags, final long data, final long udata) {
        if (size == capacity) {
            grow();
        }
        KqueueIO.evSet(kevent.asSlice(memory, size), ident, filter, flags, fflags, data, udata);
        size++;
    }

    /**
     * Reads the {@code ident} field from the kevent at the given index.
     *
     * @param index the zero-based event index
     * @return the event identifier
     */
    public long ident(final int index) {
        return KqueueIO.ident(memory, index);
    }

    /**
     * Reads the {@code filter} field from the kevent at the given index.
     *
     * @param index the zero-based event index
     * @return the event filter
     */
    public short filter(final int index) {
        return KqueueIO.filter(memory, index);
    }

    /**
     * Reads the {@code flags} field from the kevent at the given index.
     *
     * @param index the zero-based event index
     * @return the event flags
     */
    public short flags(final int index) {
        return KqueueIO.flags(memory, index);
    }

    /**
     * Reads the {@code fflags} field from the kevent at the given index.
     *
     * @param index the zero-based event index
     * @return the filter-specific flags
     */
    public int fflags(final int index) {
        return KqueueIO.fflags(memory, index);
    }

    /**
     * Reads the {@code data} field from the kevent at the given index.
     *
     * @param index the zero-based event index
     * @return the filter-specific data
     */
    public long data(final int index) {
        return KqueueIO.data(memory, index);
    }

    private void grow() {
        final int newCapacity = capacity * 2;
        final MemorySegment newMemory = kevent.allocateArray(newCapacity, arena);
        MemorySegment.copy(memory, 0, newMemory, 0, size * KqueueIO.KEVENT_SIZE);
        memory = newMemory;
        capacity = newCapacity;
    }
}
