package io.netty.ffm.macos;

import io.netty.channel.IoHandler;
import io.netty.channel.IoHandlerContext;
import io.netty.channel.IoRegistration;
import io.netty.channel.kqueue.KQueueIoEvent;
import io.netty.channel.kqueue.KQueueIoHandle;
import io.netty.channel.kqueue.KQueueIoOps;
import io.netty.ffm.ErrnoState;
import io.netty.ffm.macos.generated.BsdSocket;
import io.netty.ffm.posix.FileIO;
import io.netty.util.concurrent.ThreadAwareExecutor;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KQueueFfmIoHandlerTest {

    @Test
    void registerAndReceiveReadEvent() throws Exception {
        try (final TestHarness harness = new TestHarness()) {
            try (final Arena arena = Arena.ofShared()) {
                final MemorySegment pipefd = arena.allocate(ValueLayout.JAVA_INT, 2);
                assertEquals(0, FileIO.pipe(pipefd));
                final int readFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 0);
                final int writeFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 1);

                final CountDownLatch readLatch = new CountDownLatch(1);
                final AtomicInteger receivedFilter = new AtomicInteger();

                final KQueueIoHandle handle = new TestHandle(readFd) {
                    @Override
                    public void handle(final IoRegistration registration, final io.netty.channel.IoEvent event) {
                        final KQueueIoEvent kEvent = (KQueueIoEvent) event;
                        receivedFilter.set(kEvent.filter());
                        readLatch.countDown();
                    }
                };

                final IoRegistration registration = harness.handler.register(handle);
                registration.submit(KQueueIoOps.newOps(
                        KqueueIO.EVFILT_READ,
                        (short) (KqueueIO.EV_ADD | KqueueIO.EV_CLEAR),
                        0));

                final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
                final MemorySegment writeBuf = arena.allocate(1);
                writeBuf.set(ValueLayout.JAVA_BYTE, 0, (byte) 42);
                FileIO.write(writeFd, writeBuf, 1, capturedState);

                harness.runUntil(readLatch);
                assertEquals(KqueueIO.EVFILT_READ, (short) receivedFilter.get());

                FileIO.close(readFd);
                FileIO.close(writeFd);
            }
        }
    }

    @Test
    void registerAndReceiveWriteEvent() throws Exception {
        try (final TestHarness harness = new TestHarness()) {
            try (final Arena arena = Arena.ofShared()) {
                final MemorySegment pipefd = arena.allocate(ValueLayout.JAVA_INT, 2);
                assertEquals(0, FileIO.pipe(pipefd));
                final int readFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 0);
                final int writeFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 1);

                final CountDownLatch writeLatch = new CountDownLatch(1);
                final AtomicInteger receivedFilter = new AtomicInteger();

                final KQueueIoHandle handle = new TestHandle(writeFd) {
                    @Override
                    public void handle(final IoRegistration registration, final io.netty.channel.IoEvent event) {
                        final KQueueIoEvent kEvent = (KQueueIoEvent) event;
                        receivedFilter.set(kEvent.filter());
                        writeLatch.countDown();
                    }
                };

                final IoRegistration registration = harness.handler.register(handle);
                registration.submit(KQueueIoOps.newOps(
                        KqueueIO.EVFILT_WRITE,
                        (short) (KqueueIO.EV_ADD | KqueueIO.EV_CLEAR),
                        0));

                harness.runUntil(writeLatch);
                assertEquals(KqueueIO.EVFILT_WRITE, (short) receivedFilter.get());

                FileIO.close(readFd);
                FileIO.close(writeFd);
            }
        }
    }

    @Test
    void cancelRegistration() throws Exception {
        try (final TestHarness harness = new TestHarness()) {
            try (final Arena arena = Arena.ofShared()) {
                final MemorySegment pipefd = arena.allocate(ValueLayout.JAVA_INT, 2);
                assertEquals(0, FileIO.pipe(pipefd));
                final int readFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 0);
                final int writeFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 1);

                final AtomicInteger handleCount = new AtomicInteger();

                final KQueueIoHandle handle = new TestHandle(readFd) {
                    @Override
                    public void handle(final IoRegistration registration, final io.netty.channel.IoEvent event) {
                        handleCount.incrementAndGet();
                    }
                };

                final IoRegistration registration = harness.handler.register(handle);
                assertTrue(registration.isValid());
                assertTrue(registration.cancel());
                assertFalse(registration.isValid());

                harness.runOnce();

                final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
                final MemorySegment writeBuf = arena.allocate(1);
                writeBuf.set(ValueLayout.JAVA_BYTE, 0, (byte) 42);
                FileIO.write(writeFd, writeBuf, 1, capturedState);

                harness.runOnce();
                assertEquals(0, handleCount.get());

                FileIO.close(readFd);
                FileIO.close(writeFd);
            }
        }
    }

    @Test
    void wakeupFromExternalThread() throws Exception {
        final CountDownLatch woken = new CountDownLatch(1);
        final AtomicReference<IoHandler> handlerRef = new AtomicReference<>();

        final Thread loopThread = new Thread(() -> {
            final Thread self = Thread.currentThread();
            final ThreadAwareExecutor executor = new ThreadAwareExecutor() {
                @Override
                public boolean isExecutorThread(final Thread thread) {
                    return thread == self;
                }

                @Override
                public void execute(final Runnable command) {
                    command.run();
                }
            };
            final IoHandler handler = KQueueFfmIoHandler.newFactory().newHandler(executor);
            handlerRef.set(handler);
            handler.run(new BlockingContext());
            handler.prepareToDestroy();
            handler.destroy();
            woken.countDown();
        });
        loopThread.start();

        // Wait for handler to be created and blocking in kevent
        while (handlerRef.get() == null) {
            Thread.sleep(10);
        }
        Thread.sleep(50);

        handlerRef.get().wakeup();

        assertTrue(woken.await(2, TimeUnit.SECONDS));
        loopThread.join(2000);
    }

    @Test
    void tcpEchoThroughHandler() throws Exception {
        try (final TestHarness harness = new TestHarness()) {
            try (final Arena arena = Arena.ofShared()) {
                final MemorySegment capturedState = arena.allocate(ErrnoState.layout());

                final NativeSocket serverSocket = NativeSocket.newStreamSocket(BsdSocket.AF_INET());
                serverSocket.setNonBlocking();
                serverSocket.setReuseAddress(arena, true);
                serverSocket.bind(arena, new InetSocketAddress("127.0.0.1", 0));
                serverSocket.listen(1);
                final InetSocketAddress serverAddr = serverSocket.localAddress(arena);

                final NativeSocket clientSocket = NativeSocket.newStreamSocket(BsdSocket.AF_INET());
                clientSocket.setNonBlocking();
                clientSocket.connect(arena, serverAddr, capturedState);

                final AtomicReference<NativeSocket> acceptedRef = new AtomicReference<>();
                final CountDownLatch acceptLatch = new CountDownLatch(1);
                final CountDownLatch echoLatch = new CountDownLatch(1);
                final AtomicInteger echoedByte = new AtomicInteger(-1);

                final KQueueIoHandle serverHandle = new TestHandle(serverSocket.fd()) {
                    @Override
                    public void handle(final IoRegistration registration, final io.netty.channel.IoEvent event) {
                        final long result = SocketIO.accept(
                                serverSocket.fd(), MemorySegment.NULL, MemorySegment.NULL, capturedState);
                        final int acceptedFd = ErrnoState.unpackResult(result);
                        if (acceptedFd >= 0) {
                            final NativeSocket accepted = NativeSocket.fromFd(acceptedFd, BsdSocket.AF_INET());
                            accepted.setNonBlocking();
                            acceptedRef.set(accepted);
                            acceptLatch.countDown();
                        }
                    }
                };

                final IoRegistration serverReg = harness.handler.register(serverHandle);
                serverReg.submit(KQueueIoOps.newOps(
                        KqueueIO.EVFILT_READ,
                        (short) (KqueueIO.EV_ADD | KqueueIO.EV_CLEAR),
                        0));

                harness.runUntil(acceptLatch);
                assertNotNull(acceptedRef.get());

                final NativeSocket accepted = acceptedRef.get();
                final KQueueIoHandle acceptedHandle = new TestHandle(accepted.fd()) {
                    @Override
                    public void handle(final IoRegistration registration, final io.netty.channel.IoEvent event) {
                        final KQueueIoEvent kEvent = (KQueueIoEvent) event;
                        if (kEvent.filter() == KqueueIO.EVFILT_READ) {
                            final MemorySegment buf = arena.allocate(1);
                            final long r = FileIO.read(accepted.fd(), buf, 1, capturedState);
                            if (ErrnoState.unpackResult(r) > 0) {
                                echoedByte.set(buf.get(ValueLayout.JAVA_BYTE, 0) & 0xFF);
                                echoLatch.countDown();
                            }
                        }
                    }
                };

                final IoRegistration acceptedReg = harness.handler.register(acceptedHandle);
                acceptedReg.submit(KQueueIoOps.newOps(
                        KqueueIO.EVFILT_READ,
                        (short) (KqueueIO.EV_ADD | KqueueIO.EV_CLEAR),
                        0));

                final MemorySegment writeBuf = arena.allocate(1);
                writeBuf.set(ValueLayout.JAVA_BYTE, 0, (byte) 0xAB);
                FileIO.write(clientSocket.fd(), writeBuf, 1, capturedState);

                harness.runUntil(echoLatch);
                assertEquals(0xAB, echoedByte.get());

                serverReg.cancel();
                acceptedReg.cancel();
                harness.runOnce();
                accepted.close();
                serverSocket.close();
                clientSocket.close();
            }
        }
    }

    private static final class TestHarness implements AutoCloseable {
        final IoHandler handler;
        private final Thread testThread = Thread.currentThread();

        TestHarness() {
            final ThreadAwareExecutor executor = new ThreadAwareExecutor() {
                @Override
                public boolean isExecutorThread(final Thread thread) {
                    return thread == testThread;
                }

                @Override
                public void execute(final Runnable command) {
                    command.run();
                }
            };
            handler = KQueueFfmIoHandler.newFactory().newHandler(executor);
        }

        void runOnce() {
            handler.run(new NonBlockingContext());
        }

        void runUntil(final CountDownLatch latch) throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (latch.getCount() > 0) {
                handler.run(new NonBlockingContext());
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("Timed out waiting for latch");
                }
            }
        }

        @Override
        public void close() {
            handler.prepareToDestroy();
            handler.destroy();
        }
    }

    private static class TestHandle implements KQueueIoHandle {
        private final int fd;

        TestHandle(final int fd) {
            this.fd = fd;
        }

        @Override
        public int ident() {
            return fd;
        }

        @Override
        public void handle(final IoRegistration registration, final io.netty.channel.IoEvent event) {
        }

        @Override
        public void close() {
        }
    }

    private static final class NonBlockingContext implements IoHandlerContext {
        @Override
        public boolean canBlock() {
            return false;
        }

        @Override
        public long delayNanos(final long currentTimeNanos) {
            return 0;
        }

        @Override
        public long deadlineNanos() {
            return -1;
        }
    }

    private static final class BlockingContext implements IoHandlerContext {
        @Override
        public boolean canBlock() {
            return true;
        }

        @Override
        public long delayNanos(final long currentTimeNanos) {
            return TimeUnit.SECONDS.toNanos(30);
        }

        @Override
        public long deadlineNanos() {
            return System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        }
    }
}
