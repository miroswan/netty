package io.netty.ffm.unix;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.unix.generated.BsdSocket;
import io.netty.ffm.unix.generated.Errno;
import io.netty.ffm.unix.generated.Fcntl;
import io.netty.ffm.unix.generated.sockaddr_in;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketIOTest {

    @Test
    void createAndCloseSocket() {
        final int fd = SocketIO.socket(BsdSocket.AF_INET(), BsdSocket.SOCK_STREAM(), 0);
        assertTrue(fd >= 0, "socket() returned " + fd);
        assertEquals(0, FileIO.close(fd));
    }

    @Test
    void bindAndListen() {
        try (Arena arena = Arena.ofConfined()) {
            final int fd = SocketIO.socket(BsdSocket.AF_INET(), BsdSocket.SOCK_STREAM(), 0);
            assertTrue(fd >= 0);

            final MemorySegment addr = sockaddr_in.allocate(arena);
            sockaddr_in.sin_len(addr, (byte) sockaddr_in.sizeof());
            sockaddr_in.sin_family(addr, (byte) BsdSocket.AF_INET());
            sockaddr_in.sin_port(addr, (short) 0);

            assertEquals(0, SocketIO.bind(fd, addr, (int) sockaddr_in.sizeof()));
            assertEquals(0, SocketIO.listen(fd, 128));

            FileIO.close(fd);
        }
    }

    @Test
    void acceptWouldBlockOnNonBlockingSocket() {
        try (Arena arena = Arena.ofConfined()) {
            final int fd = SocketIO.socket(BsdSocket.AF_INET(), BsdSocket.SOCK_STREAM(), 0);
            assertTrue(fd >= 0);

            final MemorySegment addr = sockaddr_in.allocate(arena);
            sockaddr_in.sin_len(addr, (byte) sockaddr_in.sizeof());
            sockaddr_in.sin_family(addr, (byte) BsdSocket.AF_INET());
            sockaddr_in.sin_port(addr, (short) 0);

            assertEquals(0, SocketIO.bind(fd, addr, (int) sockaddr_in.sizeof()));
            assertEquals(0, SocketIO.listen(fd, 128));

            final int flags = SocketOptions.fcntl(fd, Fcntl.F_GETFL());
            SocketOptions.fcntl(fd, Fcntl.F_SETFL(), flags | Fcntl.O_NONBLOCK());

            final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
            final long result = SocketIO.accept(fd, MemorySegment.NULL, MemorySegment.NULL, capturedState);

            assertTrue(SocketIO.acceptWouldBlock(result));

            FileIO.close(fd);
        }
    }

    @Test
    void connectNonBlockingIsInProgress() {
        try (Arena arena = Arena.ofConfined()) {
            final int listenFd = SocketIO.socket(BsdSocket.AF_INET(), BsdSocket.SOCK_STREAM(), 0);
            assertTrue(listenFd >= 0);

            final MemorySegment listenAddr = sockaddr_in.allocate(arena);
            sockaddr_in.sin_len(listenAddr, (byte) sockaddr_in.sizeof());
            sockaddr_in.sin_family(listenAddr, (byte) BsdSocket.AF_INET());
            sockaddr_in.sin_port(listenAddr, (short) 0);
            sockaddr_in.sin_addr(listenAddr, arena.allocate(ValueLayout.JAVA_INT));

            assertEquals(0, SocketIO.bind(listenFd, listenAddr, (int) sockaddr_in.sizeof()));
            assertEquals(0, SocketIO.listen(listenFd, 128));

            final MemorySegment boundAddr = sockaddr_in.allocate(arena);
            final MemorySegment addrLen = arena.allocate(ValueLayout.JAVA_INT);
            addrLen.set(ValueLayout.JAVA_INT, 0, (int) sockaddr_in.sizeof());
            io.netty.ffm.unix.generated.BsdSocket.getsockname(listenFd, boundAddr, addrLen);

            final int clientFd = SocketIO.socket(BsdSocket.AF_INET(), BsdSocket.SOCK_STREAM(), 0);
            assertTrue(clientFd >= 0);

            final int flags = SocketOptions.fcntl(clientFd, Fcntl.F_GETFL());
            SocketOptions.fcntl(clientFd, Fcntl.F_SETFL(), flags | Fcntl.O_NONBLOCK());

            final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
            final long result = SocketIO.connect(clientFd, boundAddr, (int) sockaddr_in.sizeof(), capturedState);

            assertTrue(SocketIO.connectIsConnected(result) || SocketIO.connectIsInProgress(result),
                    "Expected connected or in-progress, got errno=" + ErrnoState.unpackErrno(result));

            FileIO.close(clientFd);
            FileIO.close(listenFd);
        }
    }

    @Test
    void connectSemanticHelpers() {
        assertTrue(SocketIO.connectIsConnected(ErrnoState.pack(0, 0)));
        assertTrue(SocketIO.connectIsInProgress(ErrnoState.pack(-1, Errno.EINPROGRESS())));
        assertTrue(SocketIO.connectIsInterrupted(ErrnoState.pack(-1, Errno.EINTR())));
    }

    @Test
    void shutdownUnconnectedSocketReturnsError() {
        final int fd = SocketIO.socket(BsdSocket.AF_INET(), BsdSocket.SOCK_STREAM(), 0);
        assertTrue(fd >= 0);
        assertEquals(-1, SocketIO.shutdown(fd, BsdSocket.SHUT_RDWR()));
        FileIO.close(fd);
    }
}
