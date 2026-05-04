package io.netty.ffm.posix;

import io.netty.ffm.macos.generated.iovec;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Arena-backed array of {@code struct iovec} entries for vectored I/O via
 * {@link FileIO#writev} and {@link FileIO#readv}. The caller provides the
 * {@link Arena} that owns the backing memory.
 *
 * <p>Populate entries with {@link #add(MemorySegment, long)} which sets the
 * {@code iov_base} and {@code iov_len} fields directly using cached offsets
 * for zero-allocation hot-path access. Call {@link #clear()} between batches.
 *
 * <p>After a partial write, call {@link #skip(long)} with the number of bytes
 * written to advance the logical head of the array in O(1). No memory is copied —
 * {@link #activeMemory()} returns a zero-cost slice starting at the first unconsumed
 * entry, and {@link #activeCount()} returns the number of remaining entries.
 *
 * <p>Only native (off-heap) memory segments may be added. Heap-backed segments
 * would be invisible to the GC during the native syscall, risking a segfault
 * if the garbage collector relocates the backing array mid-call.
 *
 * <p>The maximum number of entries is bounded by {@code IOV_MAX} (1024 on macOS).
 * This class does not enforce the limit — the caller is responsible for flushing
 * before reaching it.
 */
public final class IovArray {

    private static final long IOV_SIZE = iovec.sizeof();
    private static final long IOV_BASE_OFFSET = iovec.iov_base$offset();
    private static final long IOV_LEN_OFFSET = iovec.iov_len$offset();

    private final MemorySegment memory;
    private final int maxEntries;
    private int count;
    private int startIndex;
    private long totalBytes;

    /**
     * Creates a new iovec array backed by the given arena.
     *
     * @param arena the arena that owns the backing memory
     * @param maxEntries the maximum number of iovec entries to pre-allocate
     */
    public IovArray(final Arena arena, final int maxEntries) {
        this.maxEntries = maxEntries;
        this.memory = iovec.allocateArray(maxEntries, arena);
        this.count = 0;
        this.startIndex = 0;
        this.totalBytes = 0;
    }

    /**
     * Returns the memory segment sliced to the currently active (unconsumed) entries.
     * This is what should be passed to {@link FileIO#writev} or {@link FileIO#readv}.
     *
     * @return a zero-cost slice of the backing memory starting at the first unconsumed entry
     */
    public MemorySegment activeMemory() {
        return memory.asSlice(startIndex * IOV_SIZE);
    }

    /**
     * Returns the number of unconsumed iovec entries.
     *
     * @return the active entry count
     */
    public int activeCount() {
        return count - startIndex;
    }

    /**
     * Returns the total number of bytes across all unconsumed entries.
     *
     * @return the cumulative byte count of active entries
     */
    public long totalBytes() {
        return totalBytes;
    }

    /**
     * Returns {@code true} if no unconsumed entries remain.
     *
     * @return {@code true} if the array is empty
     */
    public boolean isEmpty() {
        return startIndex == count;
    }

    /**
     * Returns {@code true} if the array has reached its maximum capacity.
     *
     * @return {@code true} if no more entries can be added
     */
    public boolean isFull() {
        return count == maxEntries;
    }

    /**
     * Adds an iovec entry pointing to the given native buffer region. The segment
     * must be backed by off-heap (native) memory — heap-backed segments are rejected
     * because the GC could relocate them during the native syscall.
     *
     * @param base a native memory segment whose memory must remain valid until the
     *             writev/readv call completes
     * @param len the number of bytes in the buffer
     * @return {@code true} if the entry was added, {@code false} if the array is full
     *         or the length is zero
     * @throws IllegalArgumentException if {@code base} is not a native memory segment
     */
    public boolean add(final MemorySegment base, final long len) {
        if (!base.isNative()) {
            throw new IllegalArgumentException("iovec base must be a native memory segment");
        }
        if (len == 0 || count == maxEntries) {
            return false;
        }
        final long offset = count * IOV_SIZE;
        memory.set(ValueLayout.ADDRESS, offset + IOV_BASE_OFFSET, base);
        memory.set(ValueLayout.JAVA_LONG, offset + IOV_LEN_OFFSET, len);
        count++;
        totalBytes += len;
        return true;
    }

    /**
     * Advances the logical head of the array by the specified number of bytes in O(1).
     * Fully consumed entries are skipped by incrementing the start index. A partially
     * consumed entry has its {@code iov_base} advanced and {@code iov_len} reduced
     * in place. No memory is copied.
     *
     * @param bytes the number of bytes successfully written or read
     */
    public void skip(final long bytes) {
        if (bytes <= 0) {
            return;
        }
        if (bytes >= totalBytes) {
            clear();
            return;
        }

        totalBytes -= bytes;
        long remainingToSkip = bytes;

        for (int i = startIndex; i < count && remainingToSkip > 0; i++) {
            final long offset = i * IOV_SIZE;
            final long iovLen = memory.get(ValueLayout.JAVA_LONG, offset + IOV_LEN_OFFSET);

            if (remainingToSkip >= iovLen) {
                remainingToSkip -= iovLen;
                startIndex++;
            } else {
                final MemorySegment iovBase = memory.get(ValueLayout.ADDRESS, offset + IOV_BASE_OFFSET);
                memory.set(ValueLayout.ADDRESS, offset + IOV_BASE_OFFSET,
                        MemorySegment.ofAddress(iovBase.address() + remainingToSkip));
                memory.set(ValueLayout.JAVA_LONG, offset + IOV_LEN_OFFSET, iovLen - remainingToSkip);
                break;
            }
        }
    }

    /**
     * Resets the array to empty without deallocating memory.
     */
    public void clear() {
        count = 0;
        startIndex = 0;
        totalBytes = 0;
    }
}
