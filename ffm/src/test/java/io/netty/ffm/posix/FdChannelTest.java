package io.netty.ffm.posix;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.macos.generated.Errno;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FdChannelTest {

    @Test
    void pipeWriteAndReadRoundTrip() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment pipefd = arena.allocate(ValueLayout.JAVA_INT, 2);
            assertEquals(0, FileIO.pipe(pipefd));

            try (FdChannel reader = new FdChannel(pipefd.getAtIndex(ValueLayout.JAVA_INT, 0));
                 FdChannel writer = new FdChannel(pipefd.getAtIndex(ValueLayout.JAVA_INT, 1))) {

                final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
                final byte[] payload = "hello fd".getBytes();
                final MemorySegment writeBuf = arena.allocateFrom(ValueLayout.JAVA_BYTE, payload);

                final long writeResult = writer.write(writeBuf, payload.length, capturedState);
                assertEquals(payload.length, ErrnoState.unpackResult(writeResult));

                final MemorySegment readBuf = arena.allocate(payload.length);
                final long readResult = reader.read(readBuf, payload.length, capturedState);
                assertEquals(payload.length, ErrnoState.unpackResult(readResult));

                final byte[] received = new byte[payload.length];
                MemorySegment.copy(readBuf, ValueLayout.JAVA_BYTE, 0, received, 0, payload.length);
                assertEquals("hello fd", new String(received));
            }
        }
    }

    @Test
    void readAfterCloseReturnsEbadf() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment pipefd = arena.allocate(ValueLayout.JAVA_INT, 2);
            assertEquals(0, FileIO.pipe(pipefd));

            final FdChannel reader = new FdChannel(pipefd.getAtIndex(ValueLayout.JAVA_INT, 0));
            final FdChannel writer = new FdChannel(pipefd.getAtIndex(ValueLayout.JAVA_INT, 1));
            reader.close();
            writer.close();

            final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
            final MemorySegment buf = arena.allocate(16);
            final long result = reader.read(buf, 16, capturedState);

            assertEquals(-1, ErrnoState.unpackResult(result));
            assertEquals(Errno.EBADF(), ErrnoState.unpackErrno(result));
        }
    }

    @Test
    void fdAccessor() {
        final FdChannel channel = new FdChannel(42);
        assertEquals(42, channel.fd());
    }

    @Test
    void tryWithResourcesCloses() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment pipefd = arena.allocate(ValueLayout.JAVA_INT, 2);
            assertEquals(0, FileIO.pipe(pipefd));

            final int readFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 0);
            final int writeFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 1);

            try (FdChannel reader = new FdChannel(readFd);
                 FdChannel writer = new FdChannel(writeFd)) {
                assertTrue(reader.fd() >= 0);
                assertTrue(writer.fd() >= 0);
            }

            final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
            final MemorySegment buf = arena.allocate(16);
            final long result = FileIO.read(readFd, buf, 16, capturedState);
            assertEquals(Errno.EBADF(), ErrnoState.unpackErrno(result));
        }
    }
}
