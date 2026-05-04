package io.netty.ffm.macos;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.macos.generated.BsdSocket;
import io.netty.ffm.posix.FileIO;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLoopTest {

    @Test
    void startAndShutdown() throws Exception {
        final EventLoop loop = new EventLoop();
        final Thread thread = new Thread(loop, "test-loop");
        thread.start();

        final CompletableFuture<Void> future = loop.shutdown();
        future.get(2, TimeUnit.SECONDS);
        thread.join(2000);
    }

    @Test
    void executeFromExternalThread() throws Exception {
        final EventLoop loop = new EventLoop();
        final Thread thread = new Thread(loop, "test-loop");
        thread.start();

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ranOnLoopThread = new AtomicBoolean(false);

        loop.execute(() -> {
            ranOnLoopThread.set(loop.inEventLoop());
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertTrue(ranOnLoopThread.get());
        loop.close();
    }

    @Test
    void wakeupFromExternalThread() throws Exception {
        final EventLoop loop = new EventLoop();
        final Thread thread = new Thread(loop, "test-loop");
        thread.start();

        final CountDownLatch latch = new CountDownLatch(1);
        loop.execute(latch::countDown);

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        loop.close();
    }

    @Test
    void registerHandlerAndReceiveReadEvent() throws Exception {
        final EventLoop loop = new EventLoop();
        final Thread thread = new Thread(loop, "test-loop");
        thread.start();

        try (Arena arena = Arena.ofShared()) {
            final MemorySegment pipefd = arena.allocate(ValueLayout.JAVA_INT, 2);
            assertEquals(0, FileIO.pipe(pipefd));
            final int readFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 0);
            final int writeFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 1);

            final CountDownLatch readLatch = new CountDownLatch(1);
            final AtomicInteger bytesReceived = new AtomicInteger(0);

            final EventHandler handler = new EventHandler() {
                @Override
                public int fd() {
                    return readFd;
                }

                @Override
                public void onRead() {
                    final MemorySegment buf = loop.arena().allocate(64);
                    final long result = FileIO.read(readFd, buf, 64, loop.capturedState());
                    final int n = ErrnoState.unpackResult(result);
                    if (n > 0) {
                        bytesReceived.set(n);
                        readLatch.countDown();
                    }
                }

                @Override
                public void onWrite() {
                }

                @Override
                public void onEof() {
                }

                @Override
                public void onError() {
                }

                @Override
                public boolean readPending() {
                    return false;
                }

                @Override
                public void close() {
                    FileIO.close(readFd);
                }
            };

            loop.execute(() -> {
                final EventRegistration reg = loop.register(handler);
                reg.subscribeRead();
            });

            Thread.sleep(50);

            final MemorySegment writeBuf = arena.allocateFrom(ValueLayout.JAVA_BYTE, "hello".getBytes());
            FileIO.write(writeFd, writeBuf, 5,
                    arena.allocate(ErrnoState.layout()));

            assertTrue(readLatch.await(2, TimeUnit.SECONDS));
            assertEquals(5, bytesReceived.get());

            FileIO.close(writeFd);
        } finally {
            loop.close();
        }
    }

    @Test
    void acceptNewConnectionThroughEventLoop() throws Exception {
        final EventLoop loop = new EventLoop();
        final Thread thread = new Thread(loop, "test-loop");
        thread.start();

        final CountDownLatch acceptLatch = new CountDownLatch(1);
        final AtomicReference<NativeSocket> acceptedRef = new AtomicReference<>();

        try {
            final NativeSocket server = NativeSocket.newStreamSocket(BsdSocket.AF_INET());

            loop.execute(() -> {
                server.setReuseAddress(loop.arena(), true);
                server.bind(loop.arena(), new InetSocketAddress("127.0.0.1", 0));
                server.listen(128);
                server.setNonBlocking();

                final EventRegistration reg = loop.register(new EventHandler() {
                    @Override
                    public int fd() {
                        return server.fd();
                    }

                    @Override
                    public void onRead() {
                        final long result = server.accept(loop.capturedState());
                        if (SocketIO.acceptIsSuccess(result)) {
                            final int acceptedFd = ErrnoState.unpackResult(result);
                            final NativeSocket accepted = NativeSocket.fromFd(
                                    acceptedFd, BsdSocket.AF_INET());
                            accepted.setNonBlocking();
                            acceptedRef.set(accepted);
                            acceptLatch.countDown();
                        }
                    }

                    @Override
                    public void onWrite() {
                    }

                    @Override
                    public void onEof() {
                    }

                    @Override
                    public void onError() {
                    }

                    @Override
                    public boolean readPending() {
                        return false;
                    }

                    @Override
                    public void close() {
                        server.close();
                    }
                });
                reg.subscribeRead();
            });

            Thread.sleep(50);

            final InetSocketAddress serverAddr;
            try (Arena tempArena = Arena.ofConfined()) {
                serverAddr = server.localAddress(tempArena);
            }
            assertNotNull(serverAddr);

            try (NativeSocket client = NativeSocket.newStreamSocket(BsdSocket.AF_INET());
                 Arena tempArena = Arena.ofConfined()) {
                client.connect(tempArena, serverAddr, tempArena.allocate(ErrnoState.layout()));

                assertTrue(acceptLatch.await(2, TimeUnit.SECONDS));
                assertNotNull(acceptedRef.get());
                assertTrue(acceptedRef.get().fd() >= 0);

                acceptedRef.get().close();
            }
        } finally {
            loop.close();
        }
    }

    @Test
    void writeReadinessDetection() throws Exception {
        final EventLoop loop = new EventLoop();
        final Thread thread = new Thread(loop, "test-loop");
        thread.start();

        final CountDownLatch writeLatch = new CountDownLatch(1);

        try (Arena arena = Arena.ofShared()) {
            final MemorySegment pipefd = arena.allocate(ValueLayout.JAVA_INT, 2);
            assertEquals(0, FileIO.pipe(pipefd));
            final int readFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 0);
            final int writeFd = pipefd.getAtIndex(ValueLayout.JAVA_INT, 1);

            loop.execute(() -> {
                final EventRegistration reg = loop.register(new EventHandler() {
                    @Override
                    public int fd() {
                        return writeFd;
                    }

                    @Override
                    public void onRead() {
                    }

                    @Override
                    public void onWrite() {
                        writeLatch.countDown();
                    }

                    @Override
                    public void onEof() {
                    }

                    @Override
                    public void onError() {
                    }

                    @Override
                    public boolean readPending() {
                        return false;
                    }

                    @Override
                    public void close() {
                        FileIO.close(writeFd);
                    }
                });
                reg.subscribeWrite();
            });

            assertTrue(writeLatch.await(2, TimeUnit.SECONDS));
            FileIO.close(readFd);
        } finally {
            loop.close();
        }
    }

    @Test
    void multipleCommandsExecuteInOrder() throws Exception {
        final EventLoop loop = new EventLoop();
        final Thread thread = new Thread(loop, "test-loop");
        thread.start();

        final int[] order = new int[3];
        final CountDownLatch latch = new CountDownLatch(3);

        loop.execute(() -> {
            order[0] = 1;
            latch.countDown();
        });
        loop.execute(() -> {
            order[1] = 2;
            latch.countDown();
        });
        loop.execute(() -> {
            order[2] = 3;
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, order[0]);
        assertEquals(2, order[1]);
        assertEquals(3, order[2]);

        loop.close();
    }
}
