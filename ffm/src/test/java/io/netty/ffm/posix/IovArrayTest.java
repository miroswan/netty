package io.netty.ffm.posix;

import io.netty.ffm.ErrnoState;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IovArrayTest {

    @Test
    void addAndCheckState() {
        try (Arena arena = Arena.ofConfined()) {
            final IovArray iov = new IovArray(arena, 16);
            assertTrue(iov.isEmpty());
            assertEquals(0, iov.activeCount());
            assertEquals(0, iov.totalBytes());

            final MemorySegment buf = arena.allocate(100);
            assertTrue(iov.add(buf, 100));

            assertFalse(iov.isEmpty());
            assertEquals(1, iov.activeCount());
            assertEquals(100, iov.totalBytes());
        }
    }

    @Test
    void addMultipleEntries() {
        try (Arena arena = Arena.ofConfined()) {
            final IovArray iov = new IovArray(arena, 16);
            final MemorySegment buf1 = arena.allocate(50);
            final MemorySegment buf2 = arena.allocate(75);
            final MemorySegment buf3 = arena.allocate(25);

            assertTrue(iov.add(buf1, 50));
            assertTrue(iov.add(buf2, 75));
            assertTrue(iov.add(buf3, 25));

            assertEquals(3, iov.activeCount());
            assertEquals(150, iov.totalBytes());
        }
    }

    @Test
    void rejectZeroLength() {
        try (Arena arena = Arena.ofConfined()) {
            final IovArray iov = new IovArray(arena, 16);
            final MemorySegment buf = arena.allocate(100);
            assertFalse(iov.add(buf, 0));
            assertEquals(0, iov.activeCount());
        }
    }

    @Test
    void rejectWhenFull() {
        try (Arena arena = Arena.ofConfined()) {
            final IovArray iov = new IovArray(arena, 2);
            final MemorySegment buf = arena.allocate(10);
            assertTrue(iov.add(buf, 10));
            assertTrue(iov.add(buf, 10));
            assertTrue(iov.isFull());
            assertFalse(iov.add(buf, 10));
        }
    }

    @Test
    void rejectHeapSegment() {
        try (Arena arena = Arena.ofConfined()) {
            final IovArray iov = new IovArray(arena, 16);
            final MemorySegment heapSeg = MemorySegment.ofArray(new byte[100]);
            assertThrows(IllegalArgumentException.class, () -> iov.add(heapSeg, 100));
        }
    }

    @Test
    void clearResetsState() {
        try (Arena arena = Arena.ofConfined()) {
            final IovArray iov = new IovArray(arena, 16);
            final MemorySegment buf = arena.allocate(100);
            iov.add(buf, 100);
            iov.add(buf, 50);

            iov.clear();
            assertTrue(iov.isEmpty());
            assertEquals(0, iov.activeCount());
            assertEquals(0, iov.totalBytes());
        }
    }

    @Test
    void skipFullyConsumesFirstBuffer() {
        try (Arena arena = Arena.ofConfined()) {
            final IovArray iov = new IovArray(arena, 16);
            final MemorySegment buf1 = arena.allocate(100);
            final MemorySegment buf2 = arena.allocate(200);
            iov.add(buf1, 100);
            iov.add(buf2, 200);

            iov.skip(100);

            assertEquals(1, iov.activeCount());
            assertEquals(200, iov.totalBytes());
        }
    }

    @Test
    void skipPartiallyConsumesBuffer() {
        try (Arena arena = Arena.ofConfined()) {
            final IovArray iov = new IovArray(arena, 16);
            final MemorySegment buf = arena.allocate(100);
            iov.add(buf, 100);

            iov.skip(40);

            assertEquals(1, iov.activeCount());
            assertEquals(60, iov.totalBytes());
        }
    }

    @Test
    void skipAcrossMultipleBuffers() {
        try (Arena arena = Arena.ofConfined()) {
            final IovArray iov = new IovArray(arena, 16);
            final MemorySegment buf1 = arena.allocate(50);
            final MemorySegment buf2 = arena.allocate(75);
            final MemorySegment buf3 = arena.allocate(25);
            iov.add(buf1, 50);
            iov.add(buf2, 75);
            iov.add(buf3, 25);

            iov.skip(80);

            assertEquals(2, iov.activeCount());
            assertEquals(70, iov.totalBytes());
        }
    }

    @Test
    void skipAllClearsArray() {
        try (Arena arena = Arena.ofConfined()) {
            final IovArray iov = new IovArray(arena, 16);
            final MemorySegment buf = arena.allocate(100);
            iov.add(buf, 100);

            iov.skip(100);

            assertTrue(iov.isEmpty());
            assertEquals(0, iov.activeCount());
            assertEquals(0, iov.totalBytes());
        }
    }

    @Test
    void skipMoreThanTotalClearsArray() {
        try (Arena arena = Arena.ofConfined()) {
            final IovArray iov = new IovArray(arena, 16);
            final MemorySegment buf = arena.allocate(100);
            iov.add(buf, 100);

            iov.skip(500);

            assertTrue(iov.isEmpty());
        }
    }

    @Test
    void skipZeroIsNoop() {
        try (Arena arena = Arena.ofConfined()) {
            final IovArray iov = new IovArray(arena, 16);
            final MemorySegment buf = arena.allocate(100);
            iov.add(buf, 100);

            iov.skip(0);

            assertEquals(1, iov.activeCount());
            assertEquals(100, iov.totalBytes());
        }
    }

    @Test
    void writevWithIovArray() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment pipefd = arena.allocate(ValueLayout.JAVA_INT, 2);
            assertEquals(0, FileIO.pipe(pipefd));

            final int readFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 0);
            final int writeFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 1);
            final MemorySegment capturedState = arena.allocate(ErrnoState.layout());

            final MemorySegment buf1 = arena.allocateFrom(ValueLayout.JAVA_BYTE, "hello ".getBytes());
            final MemorySegment buf2 = arena.allocateFrom(ValueLayout.JAVA_BYTE, "world".getBytes());

            final IovArray iov = new IovArray(arena, 16);
            iov.add(buf1, 6);
            iov.add(buf2, 5);

            final long writeResult = FileIO.writev(
                    writeFd, iov.activeMemory(), iov.activeCount(), capturedState);
            assertEquals(11, ErrnoState.unpackResult(writeResult));

            final MemorySegment readBuf = arena.allocate(11);
            final long readResult = FileIO.read(readFd, readBuf, 11, capturedState);
            assertEquals(11, ErrnoState.unpackResult(readResult));

            final byte[] received = new byte[11];
            MemorySegment.copy(readBuf, ValueLayout.JAVA_BYTE, 0, received, 0, 11);
            assertEquals("hello world", new String(received));

            FileIO.close(readFd);
            FileIO.close(writeFd);
        }
    }
}
