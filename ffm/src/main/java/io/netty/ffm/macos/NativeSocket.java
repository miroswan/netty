package io.netty.ffm.macos;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.macos.generated.BsdSocket;
import io.netty.ffm.macos.generated.Fcntl;
import io.netty.ffm.macos.generated.In;
import io.netty.ffm.macos.generated.Tcp;
import io.netty.ffm.posix.FileDescriptor;
import io.netty.ffm.posix.SocketOptions;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;

/**
 * A socket backed by macOS FFM syscalls. Extends {@link FileDescriptor} to inherit
 * read/write/writev/readv operations and adds socket lifecycle methods (bind, listen,
 * connect, accept, shutdown) and typed socket option accessors.
 *
 * <p>Create instances via the static factory methods ({@link #newStreamSocket},
 * {@link #newDatagramSocket}) rather than the constructor.
 *
 * <p>Socket options that take or return integer values use a caller-provided
 * {@link SegmentAllocator} to avoid internal allocation.
 */
public final class NativeSocket extends FileDescriptor {

    private final int family;
    private boolean inputShutdown;
    private boolean outputShutdown;
    private boolean closed;

    private NativeSocket(final int fd, final int family) {
        super(fd);
        this.family = family;
    }

    /**
     * Returns the address family of this socket.
     *
     * @return the address family ({@code AF_INET}, {@code AF_INET6}, or {@code AF_UNIX})
     */
    public int family() {
        return family;
    }

    /**
     * Specifies which direction(s) of a socket to shut down.
     */
    public enum ShutdownMode {
        /** Shut down the read side. */
        READ,
        /** Shut down the write side. */
        WRITE,
        /** Shut down both read and write. */
        READ_WRITE
    }

    // --- Factory methods ---

    /**
     * Creates a new TCP stream socket for the given address family.
     *
     * @param family the address family ({@code AF_INET} or {@code AF_INET6})
     * @return a new stream socket
     * @throws IllegalStateException if socket creation fails
     */
    public static NativeSocket newStreamSocket(final int family) {
        final int fd = SocketIO.socket(family, BsdSocket.SOCK_STREAM(), 0);
        if (fd < 0) {
            throw new IllegalStateException("Failed to create stream socket");
        }
        return new NativeSocket(fd, family);
    }

    /**
     * Creates a new UDP datagram socket for the given address family.
     *
     * @param family the address family ({@code AF_INET} or {@code AF_INET6})
     * @return a new datagram socket
     * @throws IllegalStateException if socket creation fails
     */
    public static NativeSocket newDatagramSocket(final int family) {
        final int fd = SocketIO.socket(family, BsdSocket.SOCK_DGRAM(), 0);
        if (fd < 0) {
            throw new IllegalStateException("Failed to create datagram socket");
        }
        return new NativeSocket(fd, family);
    }

    /**
     * Wraps an already-open socket file descriptor (e.g. from {@link #accept}).
     *
     * @param fd the open socket fd
     * @param family the address family of the socket
     * @return a new {@link NativeSocket} wrapping the fd
     */
    public static NativeSocket fromFd(final int fd, final int family) {
        return new NativeSocket(fd, family);
    }

    // --- Socket lifecycle ---

    /**
     * Binds this socket to the given address.
     *
     * @param allocator the allocator for the native sockaddr struct
     * @param address the local address to bind to
     * @return 0 on success, -1 on error
     */
    public int bind(final SegmentAllocator allocator, final InetSocketAddress address) {
        final MemorySegment sa = SockaddrUtil.toSockaddr(allocator, address);
        return SocketIO.bind(fd(), sa, SockaddrUtil.sockaddrSize(address));
    }

    /**
     * Marks this socket as passive (accepting connections).
     *
     * @param backlog the maximum pending connection queue length
     * @return 0 on success, -1 on error
     */
    public int listen(final int backlog) {
        return SocketIO.listen(fd(), backlog);
    }

    /**
     * Initiates a connection to the given address with atomic errno capture.
     *
     * @param allocator the allocator for the native sockaddr struct
     * @param address the remote address to connect to
     * @param capturedState pre-allocated segment sized to {@link ErrnoState#layout()}
     * @return a bit-packed {@code long} with connect result and errno
     * @see SocketIO#connectIsConnected(long)
     * @see SocketIO#connectIsInProgress(long)
     */
    public long connect(final SegmentAllocator allocator, final InetSocketAddress address,
                        final MemorySegment capturedState) {
        final MemorySegment sa = SockaddrUtil.toSockaddr(allocator, address);
        return SocketIO.connect(fd(), sa, SockaddrUtil.sockaddrSize(address), capturedState);
    }

    /**
     * Accepts a connection with atomic errno capture. On success, the upper 32 bits
     * of the returned packed value contain the new socket fd.
     *
     * @param capturedState pre-allocated segment sized to {@link ErrnoState#layout()}
     * @return a bit-packed {@code long} with new fd and errno
     * @see SocketIO#acceptIsSuccess(long)
     * @see SocketIO#acceptWouldBlock(long)
     */
    public long accept(final MemorySegment capturedState) {
        return SocketIO.accept(fd(), MemorySegment.NULL, MemorySegment.NULL, capturedState);
    }

    /**
     * Shuts down one or both directions of this socket.
     *
     * @param mode which direction(s) to shut down
     * @return 0 on success, -1 on error
     */
    public int shutdown(final ShutdownMode mode) {
        final int result = switch (mode) {
            case READ -> SocketIO.shutdown(fd(), BsdSocket.SHUT_RD());
            case WRITE -> SocketIO.shutdown(fd(), BsdSocket.SHUT_WR());
            case READ_WRITE -> SocketIO.shutdown(fd(), BsdSocket.SHUT_RDWR());
        };
        if (result == 0) {
            switch (mode) {
                case READ -> inputShutdown = true;
                case WRITE -> outputShutdown = true;
                case READ_WRITE -> { inputShutdown = true; outputShutdown = true; }
            }
        }
        return result;
    }

    // --- Socket options ---

    /**
     * Sets this socket to non-blocking mode.
     *
     * @return 0 on success, -1 on error
     */
    public int setNonBlocking() {
        final int flags = SocketOptions.fcntl(fd(), Fcntl.F_GETFL());
        if (flags < 0) {
            return flags;
        }
        return SocketOptions.fcntl(fd(), Fcntl.F_SETFL(), flags | Fcntl.O_NONBLOCK());
    }

    /**
     * Sets the {@code SO_REUSEADDR} option.
     *
     * @param allocator the allocator for the option value segment
     * @param enabled {@code true} to enable, {@code false} to disable
     * @return 0 on success, -1 on error
     */
    public int setReuseAddress(final SegmentAllocator allocator, final boolean enabled) {
        return setIntOption(allocator, BsdSocket.SOL_SOCKET(), BsdSocket.SO_REUSEADDR(), enabled ? 1 : 0);
    }

    /**
     * Sets the {@code SO_REUSEPORT} option.
     *
     * @param allocator the allocator for the option value segment
     * @param enabled {@code true} to enable, {@code false} to disable
     * @return 0 on success, -1 on error
     */
    public int setReusePort(final SegmentAllocator allocator, final boolean enabled) {
        return setIntOption(allocator, BsdSocket.SOL_SOCKET(), BsdSocket.SO_REUSEPORT(), enabled ? 1 : 0);
    }

    /**
     * Sets the {@code SO_KEEPALIVE} option.
     *
     * @param allocator the allocator for the option value segment
     * @param enabled {@code true} to enable, {@code false} to disable
     * @return 0 on success, -1 on error
     */
    public int setKeepAlive(final SegmentAllocator allocator, final boolean enabled) {
        return setIntOption(allocator, BsdSocket.SOL_SOCKET(), BsdSocket.SO_KEEPALIVE(), enabled ? 1 : 0);
    }

    /**
     * Sets the {@code TCP_NODELAY} option.
     *
     * @param allocator the allocator for the option value segment
     * @param enabled {@code true} to enable, {@code false} to disable
     * @return 0 on success, -1 on error
     */
    public int setTcpNoDelay(final SegmentAllocator allocator, final boolean enabled) {
        return setIntOption(allocator, In.IPPROTO_TCP(), Tcp.TCP_NODELAY(), enabled ? 1 : 0);
    }

    /**
     * Sets the {@code SO_SNDBUF} option.
     *
     * @param allocator the allocator for the option value segment
     * @param size the send buffer size in bytes
     * @return 0 on success, -1 on error
     */
    public int setSendBufferSize(final SegmentAllocator allocator, final int size) {
        return setIntOption(allocator, BsdSocket.SOL_SOCKET(), BsdSocket.SO_SNDBUF(), size);
    }

    /**
     * Sets the {@code SO_RCVBUF} option.
     *
     * @param allocator the allocator for the option value segment
     * @param size the receive buffer size in bytes
     * @return 0 on success, -1 on error
     */
    public int setReceiveBufferSize(final SegmentAllocator allocator, final int size) {
        return setIntOption(allocator, BsdSocket.SOL_SOCKET(), BsdSocket.SO_RCVBUF(), size);
    }

    /**
     * Gets the {@code SO_ERROR} value and clears it.
     *
     * @param allocator the allocator for the option value and length segments
     * @return the pending socket error, or 0 if none
     */
    public int getSoError(final SegmentAllocator allocator) {
        return getIntOption(allocator, BsdSocket.SOL_SOCKET(), BsdSocket.SO_ERROR());
    }

    /**
     * Gets the local address this socket is bound to.
     *
     * @param allocator the allocator for the native sockaddr and length segments
     * @return the local address, or {@code null} if not bound
     */
    public InetSocketAddress localAddress(final SegmentAllocator allocator) {
        return getSocketAddress(allocator, true);
    }

    /**
     * Gets the remote address this socket is connected to.
     *
     * @param allocator the allocator for the native sockaddr and length segments
     * @return the remote address, or {@code null} if not connected
     */
    public InetSocketAddress remoteAddress(final SegmentAllocator allocator) {
        return getSocketAddress(allocator, false);
    }

    /**
     * Returns whether this socket's file descriptor is still open.
     *
     * @return {@code true} if the socket has not been closed
     */
    public boolean isOpen() {
        return !closed;
    }

    /**
     * Returns whether the input side of this socket has been shut down.
     *
     * @return {@code true} if shutdown(READ) has been called
     */
    public boolean isInputShutdown() {
        return inputShutdown;
    }

    /**
     * Returns whether the output side of this socket has been shut down.
     *
     * @return {@code true} if shutdown(WRITE) has been called
     */
    public boolean isOutputShutdown() {
        return outputShutdown;
    }

    /**
     * Completes a non-blocking connect by checking {@code SO_ERROR}. Returns
     * {@code true} if the connection is established (SO_ERROR == 0), {@code false}
     * if still in progress (should not happen after EVFILT_WRITE fires), or throws
     * if SO_ERROR indicates a failure.
     *
     * @param capturedState pre-allocated segment for errno capture (used by getSoError)
     * @return {@code true} if connected successfully
     * @throws java.net.ConnectException if SO_ERROR indicates a connection failure
     */
    public boolean finishConnect(final MemorySegment capturedState) throws Exception {
        try (final Arena tempArena = Arena.ofConfined()) {
            final int soError = getSoError(tempArena);
            if (soError == 0) {
                return true;
            }
            throw new java.net.ConnectException("connect failed: errno=" + soError);
        }
    }

    /**
     * Closes this socket and marks it as closed.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            super.close();
        }
    }

    private int setIntOption(final SegmentAllocator allocator, final int level,
                             final int optname, final int value) {
        final MemorySegment optval = allocator.allocate(ValueLayout.JAVA_INT);
        optval.set(ValueLayout.JAVA_INT, 0, value);
        return SocketOptions.setsockopt(fd(), level, optname, optval,
                (int) ValueLayout.JAVA_INT.byteSize());
    }

    private int getIntOption(final SegmentAllocator allocator, final int level, final int optname) {
        final MemorySegment optval = allocator.allocate(ValueLayout.JAVA_INT);
        final MemorySegment optlen = allocator.allocate(ValueLayout.JAVA_INT);
        optlen.set(ValueLayout.JAVA_INT, 0, (int) ValueLayout.JAVA_INT.byteSize());
        final int result = SocketOptions.getsockopt(fd(), level, optname, optval, optlen);
        if (result < 0) {
            return -1;
        }
        return optval.get(ValueLayout.JAVA_INT, 0);
    }

    private InetSocketAddress getSocketAddress(final SegmentAllocator allocator, final boolean local) {
        final MemorySegment addr = allocator.allocate(128);
        final MemorySegment addrLen = allocator.allocate(ValueLayout.JAVA_INT);
        addrLen.set(ValueLayout.JAVA_INT, 0, 128);
        final int result;
        if (local) {
            result = io.netty.ffm.macos.generated.BsdSocket.getsockname(fd(), addr, addrLen);
        } else {
            result = io.netty.ffm.macos.generated.BsdSocket.getpeername(fd(), addr, addrLen);
        }
        if (result < 0) {
            return null;
        }
        return SockaddrUtil.fromSockaddr(addr);
    }
}
