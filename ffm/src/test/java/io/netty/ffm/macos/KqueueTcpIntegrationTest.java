package io.netty.ffm.macos;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.posix.IovArray;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KqueueTcpIntegrationTest {

    @Test
    void fullTcpRoundTripThroughKqueue() {
        try (KqueueTestContext ctx = KqueueTestContext.builder()
                .withServer("127.0.0.1", 0)
                .withClient()
                .build()) {

            ctx.registerEvent(ctx.accepted().fd(), KqueueIO.EVFILT_READ, KqueueIO.EV_ADD);

            ctx.clientWrite("kqueue integration test");
            ctx.pollAndExpectReadable(ctx.accepted().fd());

            final String received = ctx.readString(ctx.accepted(), 23);
            assertEquals("kqueue integration test", received);
        }
    }

    @Test
    void writevThroughKqueue() {
        try (KqueueTestContext ctx = KqueueTestContext.builder()
                .withServer("127.0.0.1", 0)
                .withClient()
                .build()) {

            final MemorySegment buf1 = ctx.arena().allocateFrom(
                    ValueLayout.JAVA_BYTE, "hello ".getBytes());
            final MemorySegment buf2 = ctx.arena().allocateFrom(
                    ValueLayout.JAVA_BYTE, "kqueue ".getBytes());
            final MemorySegment buf3 = ctx.arena().allocateFrom(
                    ValueLayout.JAVA_BYTE, "writev".getBytes());

            final IovArray iov = new IovArray(ctx.arena(), 16);
            iov.add(buf1, 6);
            iov.add(buf2, 7);
            iov.add(buf3, 6);

            final long writevResult = ctx.client().writev(
                    iov.activeMemory(), iov.activeCount(), ctx.capturedState());
            assertEquals(19, ErrnoState.unpackResult(writevResult));

            final String received = ctx.readString(ctx.accepted(), 19);
            assertEquals("hello kqueue writev", received);
        }
    }

    @Test
    void bidirectionalCommunication() {
        try (KqueueTestContext ctx = KqueueTestContext.builder()
                .withServer("127.0.0.1", 0)
                .withClient()
                .build()) {

            ctx.clientWrite("ping");
            assertEquals("ping", ctx.readString(ctx.accepted(), 4));

            ctx.acceptedWrite("pong");
            assertEquals("pong", ctx.readString(ctx.client(), 4));
        }
    }

    @Test
    void kqueueDetectsNewConnection() {
        try (KqueueTestContext ctx = KqueueTestContext.builder()
                .withServer("127.0.0.1", 0)
                .build()) {

            ctx.registerEvent(ctx.server().fd(), KqueueIO.EVFILT_READ, KqueueIO.EV_ADD);

            final NativeSocket client = NativeSocket.newStreamSocket(
                    io.netty.ffm.macos.generated.BsdSocket.AF_INET());
            client.connect(ctx.arena(), ctx.server().localAddress(ctx.arena()), ctx.capturedState());

            ctx.pollAndExpectReadable(ctx.server().fd());

            final long acceptResult = ctx.server().accept(ctx.capturedState());
            assertTrue(SocketIO.acceptIsSuccess(acceptResult));

            NativeSocket.fromFd(ErrnoState.unpackResult(acceptResult),
                    io.netty.ffm.macos.generated.BsdSocket.AF_INET()).close();
            client.close();
        }
    }

    @Test
    void multipleReadEvents() {
        try (KqueueTestContext ctx = KqueueTestContext.builder()
                .withServer("127.0.0.1", 0)
                .withClient()
                .build()) {

            ctx.registerEvent(ctx.accepted().fd(), KqueueIO.EVFILT_READ,
                    (short) (KqueueIO.EV_ADD | KqueueIO.EV_CLEAR));

            ctx.clientWrite("first");
            ctx.pollAndExpectReadable(ctx.accepted().fd());
            assertEquals("first", ctx.readString(ctx.accepted(), 5));

            ctx.clientWrite("second");
            ctx.pollAndExpectReadable(ctx.accepted().fd());
            assertEquals("second", ctx.readString(ctx.accepted(), 6));
        }
    }
}
