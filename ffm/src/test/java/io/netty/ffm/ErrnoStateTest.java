package io.netty.ffm;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrnoStateTest {

    @Test
    void packAndUnpackPositiveResult() {
        final long packed = ErrnoState.pack(42, 0);
        assertEquals(42, ErrnoState.unpackResult(packed));
        assertEquals(0, ErrnoState.unpackErrno(packed));
    }

    @Test
    void packAndUnpackNegativeOneResult() {
        final long packed = ErrnoState.pack(-1, 35);
        assertEquals(-1, ErrnoState.unpackResult(packed));
        assertEquals(35, ErrnoState.unpackErrno(packed));
    }

    @Test
    void packAndUnpackZeroResult() {
        final long packed = ErrnoState.pack(0, 0);
        assertEquals(0, ErrnoState.unpackResult(packed));
        assertEquals(0, ErrnoState.unpackErrno(packed));
    }

    @Test
    void packAndUnpackMaxIntResult() {
        final long packed = ErrnoState.pack(Integer.MAX_VALUE, 22);
        assertEquals(Integer.MAX_VALUE, ErrnoState.unpackResult(packed));
        assertEquals(22, ErrnoState.unpackErrno(packed));
    }

    @Test
    void layoutIsNonNull() {
        assertEquals(true, ErrnoState.layout().byteSize() > 0);
    }

    @Test
    void extractErrnoFromAllocatedSegment() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
            final int errno = ErrnoState.extractErrno(capturedState);
            assertEquals(0, errno);
        }
    }
}
