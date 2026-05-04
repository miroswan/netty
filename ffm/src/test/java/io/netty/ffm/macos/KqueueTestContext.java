package io.netty.ffm.macos;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.macos.generated.BsdSocket;
import io.netty.ffm.posix.FileIO;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test harness that owns a kqueue fd, arena, capturedState, and optional server/client
 * sockets. Implements {@link AutoCloseable} for use in try-with-resources blocks.
 * All resources are cleaned up on close.
 *
 * <p>Create via {@link #builder()}.
 */
final class KqueueTestContext implements AutoCloseable {

    private final Arena arena;
    private final MemorySegment capturedState;
    private final MemorySegment timeout;
    private final int kq;
    private final KQueueEventArray changelist;
    private final KQueueEventArray eventlist;
    private final NativeSocket server;
    private final NativeSocket client;
    private final NativeSocket accepted;

    private KqueueTestContext(final Arena arena, final MemorySegment capturedState,
                              final MemorySegment timeout, final int kq,
                              final KQueueEventArray changelist, final KQueueEventArray eventlist,
                              final NativeSocket server, final NativeSocket client,
                              final NativeSocket accepted) {
        this.arena = arena;
        this.capturedState = capturedState;
        this.timeout = timeout;
        this.kq = kq;
        this.changelist = changelist;
        this.eventlist = eventlist;
        this.server = server;
        this.client = client;
        this.accepted = accepted;
    }

    /**
     * Returns the arena that owns all memory in this context.
     *
     * @return the test arena
     */
    public Arena arena() {
        return arena;
    }

    /**
     * Returns the pre-allocated captured state segment for errno-safe syscalls.
     *
     * @return the capturedState segment
     */
    public MemorySegment capturedState() {
        return capturedState;
    }

    /**
     * Returns the kqueue file descriptor.
     *
     * @return the kqueue fd
     */
    public int kq() {
        return kq;
    }

    /**
     * Returns the changelist for registering kqueue events.
     *
     * @return the changelist array
     */
    public KQueueEventArray changelist() {
        return changelist;
    }

    /**
     * Returns the eventlist for receiving ready kqueue events.
     *
     * @return the eventlist array
     */
    public KQueueEventArray eventlist() {
        return eventlist;
    }

    /**
     * Returns the server socket, or {@code null} if not configured.
     *
     * @return the server socket
     */
    public NativeSocket server() {
        return server;
    }

    /**
     * Returns the client socket, or {@code null} if not configured.
     *
     * @return the client socket
     */
    public NativeSocket client() {
        return client;
    }

    /**
     * Returns the accepted socket, or {@code null} if the builder did not establish
     * a connection.
     *
     * @return the accepted socket
     */
    public NativeSocket accepted() {
        return accepted;
    }

    /**
     * Registers a kqueue event via the changelist and flushes it.
     *
     * @param fd the file descriptor to monitor
     * @param filter the event filter (e.g. {@link KqueueIO#EVFILT_READ})
     * @param flags the event flags (e.g. {@link KqueueIO#EV_ADD})
     */
    public void registerEvent(final int fd, final short filter, final short flags) {
        changelist.clear();
        changelist.add(fd, filter, flags, 0, 0L, 0L);
        final long result = KqueueIO.kevent(kq, changelist.memory(), changelist.size(),
                MemorySegment.NULL, 0, timeout, capturedState);
        assertEquals(0, ErrnoState.unpackResult(result));
    }

    /**
     * Polls kqueue and asserts at least one event is ready.
     *
     * @return the number of ready events
     */
    public int poll() {
        final long result = KqueueIO.kevent(kq, MemorySegment.NULL, 0,
                eventlist.memory(), eventlist.capacity(), timeout, capturedState);
        final int readyCount = ErrnoState.unpackResult(result);
        assertTrue(readyCount > 0, "Expected at least one ready event");
        return readyCount;
    }

    /**
     * Polls kqueue and asserts the given fd is readable.
     *
     * @param fd the expected readable file descriptor
     */
    public void pollAndExpectReadable(final int fd) {
        poll();
        assertEquals(fd, (int) eventlist.ident(0));
        assertEquals(KqueueIO.EVFILT_READ, eventlist.filter(0));
    }

    /**
     * Writes a string to the given socket.
     *
     * @param socket the socket to write to
     * @param data the string to write
     */
    public void write(final NativeSocket socket, final String data) {
        final byte[] bytes = data.getBytes();
        final MemorySegment buf = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
        final long result = socket.write(buf, bytes.length, capturedState);
        assertEquals(bytes.length, ErrnoState.unpackResult(result));
    }

    /**
     * Writes a string to the client socket.
     *
     * @param data the string to write
     */
    public void clientWrite(final String data) {
        assertNotNull(client, "Client socket not configured");
        write(client, data);
    }

    /**
     * Writes a string to the accepted socket.
     *
     * @param data the string to write
     */
    public void acceptedWrite(final String data) {
        assertNotNull(accepted, "Accepted socket not configured");
        write(accepted, data);
    }

    /**
     * Reads exactly {@code length} bytes from the given socket and returns as a string.
     * Loops to handle partial reads, which POSIX allows on stream sockets.
     *
     * @param socket the socket to read from
     * @param length the total number of bytes to read
     * @return the data as a string
     */
    public String readString(final NativeSocket socket, final int length) {
        final MemorySegment buf = arena.allocate(length);
        int totalRead = 0;
        while (totalRead < length) {
            final long result = socket.read(
                    buf.asSlice(totalRead), length - totalRead, capturedState);
            final int bytesRead = ErrnoState.unpackResult(result);
            assertTrue(bytesRead > 0, "Expected to read bytes but got " + bytesRead
                    + " (errno=" + ErrnoState.unpackErrno(result) + ")");
            totalRead += bytesRead;
        }
        final byte[] bytes = new byte[length];
        MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, bytes, 0, length);
        return new String(bytes);
    }

    @Override
    public void close() {
        if (accepted != null) {
            accepted.close();
        }
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        FileIO.close(kq);
        arena.close();
    }

    /**
     * Creates a new builder for configuring a test context.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link KqueueTestContext}. Configures optional server and client
     * sockets and establishes the TCP connection during {@link #build()}.
     */
    static final class Builder {

        private String serverHost;
        private int serverPort;
        private boolean withClient;
        private boolean nonBlocking = true;
        private int eventArrayCapacity = 8;
        private long timeoutSeconds;
        private long timeoutNanos = 50_000_000L;

        Builder() {
        }

        /**
         * Configures a server socket that will be bound and listening after build.
         *
         * @param host the address to bind to
         * @param port the port to bind to (0 for ephemeral)
         * @return this builder
         */
        public Builder withServer(final String host, final int port) {
            this.serverHost = host;
            this.serverPort = port;
            return this;
        }

        /**
         * Configures a client socket that will connect to the server and be accepted
         * during build. The handshake is performed in blocking mode for determinism,
         * then sockets are set to non-blocking if configured.
         * Requires {@link #withServer} to be called first.
         *
         * @return this builder
         */
        public Builder withClient() {
            this.withClient = true;
            return this;
        }

        /**
         * Sets the capacity for the changelist and eventlist arrays.
         *
         * @param capacity the number of kevent slots
         * @return this builder
         */
        public Builder eventArrayCapacity(final int capacity) {
            this.eventArrayCapacity = capacity;
            return this;
        }

        /**
         * Sets whether sockets should be set to non-blocking mode after the connection
         * handshake completes. Defaults to {@code true}.
         *
         * @param nonBlocking {@code true} for non-blocking sockets
         * @return this builder
         */
        public Builder nonBlocking(final boolean nonBlocking) {
            this.nonBlocking = nonBlocking;
            return this;
        }

        /**
         * Sets the kqueue poll timeout. Defaults to 50ms.
         *
         * @param seconds the seconds component
         * @param nanos the nanoseconds component
         * @return this builder
         */
        public Builder timeout(final long seconds, final long nanos) {
            this.timeoutSeconds = seconds;
            this.timeoutNanos = nanos;
            return this;
        }

        /**
         * Builds the test context, creating all configured resources. The TCP handshake
         * (if configured) is performed in blocking mode to avoid race conditions, then
         * sockets are switched to non-blocking mode.
         *
         * @return a fully initialized test context
         */
        public KqueueTestContext build() {
            final Arena arena = Arena.ofConfined();
            final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
            final MemorySegment timeout = arena.allocate(16);
            timeout.set(ValueLayout.JAVA_LONG, 0, timeoutSeconds);
            timeout.set(ValueLayout.JAVA_LONG, 8, timeoutNanos);

            final int kq = KqueueIO.kqueue();
            assertTrue(kq >= 0, "Failed to create kqueue");

            final KQueueEventArray changelist = new KQueueEventArray(arena, eventArrayCapacity);
            final KQueueEventArray eventlist = new KQueueEventArray(arena, eventArrayCapacity);

            NativeSocket server = null;
            NativeSocket client = null;
            NativeSocket accepted = null;

            if (serverHost != null) {
                server = NativeSocket.newStreamSocket(BsdSocket.AF_INET());
                assertEquals(0, server.setReuseAddress(arena, true));
                assertEquals(0, server.bind(arena, new InetSocketAddress(serverHost, serverPort)));
                assertEquals(0, server.listen(128));
            }

            if (withClient) {
                assertNotNull(server, "withClient requires withServer");
                final InetSocketAddress serverAddr = server.localAddress(arena);
                assertNotNull(serverAddr);

                client = NativeSocket.newStreamSocket(BsdSocket.AF_INET());
                final long connectResult = client.connect(arena, serverAddr, capturedState);
                assertTrue(SocketIO.connectIsConnected(connectResult));

                final long acceptResult = server.accept(capturedState);
                assertTrue(SocketIO.acceptIsSuccess(acceptResult));
                accepted = NativeSocket.fromFd(
                        ErrnoState.unpackResult(acceptResult), BsdSocket.AF_INET());

                if (nonBlocking) {
                    assertEquals(0, server.setNonBlocking());
                    assertEquals(0, client.setNonBlocking());
                    assertEquals(0, accepted.setNonBlocking());
                }
            } else if (serverHost != null && nonBlocking) {
                assertEquals(0, server.setNonBlocking());
            }

            return new KqueueTestContext(arena, capturedState, timeout, kq,
                    changelist, eventlist, server, client, accepted);
        }
    }
}
