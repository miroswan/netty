package io.netty.ffm.macos;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.macos.generated.BsdSocket;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeSocketTest {

    @Test
    void createAndCloseStreamSocket() {
        try (NativeSocket socket = NativeSocket.newStreamSocket(BsdSocket.AF_INET())) {
            assertTrue(socket.fd() >= 0);
            assertEquals(BsdSocket.AF_INET(), socket.family());
        }
    }

    @Test
    void createAndCloseDatagramSocket() {
        try (NativeSocket socket = NativeSocket.newDatagramSocket(BsdSocket.AF_INET())) {
            assertTrue(socket.fd() >= 0);
            assertEquals(BsdSocket.AF_INET(), socket.family());
        }
    }

    @Test
    void bindAndListen() {
        try (Arena arena = Arena.ofConfined();
             NativeSocket server = NativeSocket.newStreamSocket(BsdSocket.AF_INET())) {
            assertEquals(0, server.bind(arena, new InetSocketAddress("127.0.0.1", 0)));
            assertEquals(0, server.listen(128));
        }
    }

    @Test
    void localAddressAfterBind() {
        try (Arena arena = Arena.ofConfined();
             NativeSocket server = NativeSocket.newStreamSocket(BsdSocket.AF_INET())) {
            assertEquals(0, server.bind(arena, new InetSocketAddress("127.0.0.1", 0)));

            final InetSocketAddress local = server.localAddress(arena);
            assertNotNull(local);
            assertTrue(local.getPort() > 0);
        }
    }

    @Test
    void remoteAddressBeforeConnect() {
        try (Arena arena = Arena.ofConfined();
             NativeSocket socket = NativeSocket.newStreamSocket(BsdSocket.AF_INET())) {
            assertNull(socket.remoteAddress(arena));
        }
    }

    @Test
    void setNonBlocking() {
        try (NativeSocket socket = NativeSocket.newStreamSocket(BsdSocket.AF_INET())) {
            assertEquals(0, socket.setNonBlocking());
        }
    }

    @Test
    void acceptWouldBlockOnNonBlockingSocket() {
        try (Arena arena = Arena.ofConfined();
             NativeSocket server = NativeSocket.newStreamSocket(BsdSocket.AF_INET())) {
            assertEquals(0, server.bind(arena, new InetSocketAddress("127.0.0.1", 0)));
            assertEquals(0, server.listen(128));
            assertEquals(0, server.setNonBlocking());

            final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
            final long result = server.accept(capturedState);
            assertTrue(SocketIO.acceptWouldBlock(result));
        }
    }

    @Test
    void connectAndAccept() {
        try (Arena arena = Arena.ofConfined();
             NativeSocket server = NativeSocket.newStreamSocket(BsdSocket.AF_INET())) {

            assertEquals(0, server.setReuseAddress(arena, true));
            assertEquals(0, server.bind(arena, new InetSocketAddress("127.0.0.1", 0)));
            assertEquals(0, server.listen(128));

            final InetSocketAddress serverAddr = server.localAddress(arena);
            assertNotNull(serverAddr);

            try (NativeSocket client = NativeSocket.newStreamSocket(BsdSocket.AF_INET())) {
                assertEquals(0, client.setNonBlocking());
                final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
                final long connectResult = client.connect(arena, serverAddr, capturedState);
                assertTrue(SocketIO.connectIsConnected(connectResult)
                        || SocketIO.connectIsInProgress(connectResult));

                final long acceptResult = server.accept(capturedState);
                assertTrue(SocketIO.acceptIsSuccess(acceptResult));

                final int acceptedFd = ErrnoState.unpackResult(acceptResult);
                try (NativeSocket accepted = NativeSocket.fromFd(acceptedFd, BsdSocket.AF_INET())) {
                    assertTrue(accepted.fd() >= 0);
                }
            }
        }
    }

    @Test
    void socketOptions() {
        try (Arena arena = Arena.ofConfined();
             NativeSocket socket = NativeSocket.newStreamSocket(BsdSocket.AF_INET())) {
            assertEquals(0, socket.setReuseAddress(arena, true));
            assertEquals(0, socket.setReusePort(arena, true));
            assertEquals(0, socket.setKeepAlive(arena, true));
            assertEquals(0, socket.setTcpNoDelay(arena, true));
            assertEquals(0, socket.setSendBufferSize(arena, 32768));
            assertEquals(0, socket.setReceiveBufferSize(arena, 32768));
            assertEquals(0, socket.getSoError(arena));
        }
    }

    @Test
    void shutdownModes() {
        try (Arena arena = Arena.ofConfined();
             NativeSocket server = NativeSocket.newStreamSocket(BsdSocket.AF_INET())) {

            assertEquals(0, server.bind(arena, new InetSocketAddress("127.0.0.1", 0)));
            assertEquals(0, server.listen(128));

            final InetSocketAddress serverAddr = server.localAddress(arena);

            try (NativeSocket client = NativeSocket.newStreamSocket(BsdSocket.AF_INET())) {
                final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
                client.connect(arena, serverAddr, capturedState);

                final long acceptResult = server.accept(capturedState);
                assertTrue(SocketIO.acceptIsSuccess(acceptResult));

                try (NativeSocket accepted = NativeSocket.fromFd(
                        ErrnoState.unpackResult(acceptResult), BsdSocket.AF_INET())) {
                    assertEquals(0, accepted.shutdown(NativeSocket.ShutdownMode.WRITE));
                }
            }
        }
    }

    @Test
    void readWriteThroughSocket() {
        try (Arena arena = Arena.ofConfined();
             NativeSocket server = NativeSocket.newStreamSocket(BsdSocket.AF_INET())) {

            assertEquals(0, server.bind(arena, new InetSocketAddress("127.0.0.1", 0)));
            assertEquals(0, server.listen(128));
            final InetSocketAddress serverAddr = server.localAddress(arena);

            try (NativeSocket client = NativeSocket.newStreamSocket(BsdSocket.AF_INET())) {
                final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
                client.connect(arena, serverAddr, capturedState);

                final long acceptResult = server.accept(capturedState);
                assertTrue(SocketIO.acceptIsSuccess(acceptResult));

                try (NativeSocket accepted = NativeSocket.fromFd(
                        ErrnoState.unpackResult(acceptResult), BsdSocket.AF_INET())) {

                    final byte[] payload = "hello socket".getBytes();
                    final MemorySegment writeBuf = arena.allocateFrom(ValueLayout.JAVA_BYTE, payload);
                    final long writeResult = client.write(writeBuf, payload.length, capturedState);
                    assertEquals(payload.length, ErrnoState.unpackResult(writeResult));

                    final MemorySegment readBuf = arena.allocate(payload.length);
                    final long readResult = accepted.read(readBuf, payload.length, capturedState);
                    assertEquals(payload.length, ErrnoState.unpackResult(readResult));

                    final byte[] received = new byte[payload.length];
                    MemorySegment.copy(readBuf, ValueLayout.JAVA_BYTE, 0, received, 0, payload.length);
                    assertEquals("hello socket", new String(received));
                }
            }
        }
    }
}
