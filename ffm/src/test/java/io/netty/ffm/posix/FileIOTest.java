package io.netty.ffm.posix;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.macos.generated.Errno;
import io.netty.ffm.macos.generated.Fcntl;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileIOTest {

    @Test
    void pipeWriteAndRead() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment pipefd = arena.allocate(ValueLayout.JAVA_INT, 2);
            assertEquals(0, FileIO.pipe(pipefd));

            final int readFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 0);
            final int writeFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 1);
            final MemorySegment capturedState = arena.allocate(ErrnoState.layout());

            final byte[] payload = "hello".getBytes();
            final MemorySegment writeBuf = arena.allocateFrom(ValueLayout.JAVA_BYTE, payload);
            final long writeResult = FileIO.write(writeFd, writeBuf, payload.length, capturedState);
            assertEquals(payload.length, ErrnoState.unpackResult(writeResult));

            final MemorySegment readBuf = arena.allocate(payload.length);
            final long readResult = FileIO.read(readFd, readBuf, payload.length, capturedState);
            assertEquals(payload.length, ErrnoState.unpackResult(readResult));

            final byte[] received = new byte[payload.length];
            MemorySegment.copy(readBuf, ValueLayout.JAVA_BYTE, 0, received, 0, payload.length);
            assertEquals("hello", new String(received));

            FileIO.close(readFd);
            FileIO.close(writeFd);
        }
    }

    @Test
    void readFromClosedFdCapturesEbadf() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment pipefd = arena.allocate(ValueLayout.JAVA_INT, 2);
            assertEquals(0, FileIO.pipe(pipefd));

            final int readFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 0);
            final int writeFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 1);
            FileIO.close(readFd);
            FileIO.close(writeFd);

            final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
            final MemorySegment buf = arena.allocate(16);
            final long result = FileIO.read(readFd, buf, 16, capturedState);

            assertEquals(-1, ErrnoState.unpackResult(result));
            assertEquals(Errno.EBADF(), ErrnoState.unpackErrno(result));
        }
    }

    @Test
    void wouldBlockSemanticHelper() {
        final long packed = ErrnoState.pack(-1, Errno.EAGAIN());
        assertTrue(FileIO.wouldBlock(packed));
        assertFalse(FileIO.isEof(packed));
        assertFalse(FileIO.isInterrupted(packed));
    }

    @Test
    void eofSemanticHelper() {
        final long packed = ErrnoState.pack(0, 0);
        assertTrue(FileIO.isEof(packed));
        assertFalse(FileIO.wouldBlock(packed));
    }

    @Test
    void interruptedSemanticHelper() {
        final long packed = ErrnoState.pack(-1, Errno.EINTR());
        assertTrue(FileIO.isInterrupted(packed));
        assertFalse(FileIO.wouldBlock(packed));
    }

    @Test
    void fcntlSetNonBlock() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment pipefd = arena.allocate(ValueLayout.JAVA_INT, 2);
            assertEquals(0, FileIO.pipe(pipefd));

            final int readFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 0);
            final int writeFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 1);

            final int flags = SocketOptions.fcntl(readFd, Fcntl.F_GETFL());
            assertTrue(flags >= 0);

            final int setResult = SocketOptions.fcntl(readFd, Fcntl.F_SETFL(), flags | Fcntl.O_NONBLOCK());
            assertEquals(0, setResult);

            final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
            final MemorySegment buf = arena.allocate(16);
            final long readResult = FileIO.read(readFd, buf, 16, capturedState);
            assertTrue(FileIO.wouldBlock(readResult));

            FileIO.close(readFd);
            FileIO.close(writeFd);
        }
    }
}
