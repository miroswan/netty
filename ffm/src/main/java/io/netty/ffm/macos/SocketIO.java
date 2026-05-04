package io.netty.ffm.macos;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.NativeTransportException;
import io.netty.ffm.macos.generated.BsdSocket;
import io.netty.ffm.macos.generated.Errno;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Zero-allocation socket I/O wrappers with atomic errno capture. Methods that may set
 * meaningful errno values ({@code connect}, {@code accept}, {@code sendto}, {@code recvfrom})
 * return a bit-packed {@code long} decodable via {@link ErrnoState}. Methods where errno
 * is not needed for control flow ({@code socket}, {@code bind}, {@code listen},
 * {@code shutdown}) delegate directly to the jextract-generated bindings.
 *
 * <p>The caller must pre-allocate a {@code capturedState} segment from
 * {@link ErrnoState#layout()} on a long-lived arena and pass it to every errno-capturing call.
 */
public final class SocketIO {

    private static final Linker LINKER = Linker.nativeLinker();

    private static final MethodHandle CONNECT_HANDLE = LINKER.downcallHandle(
            LINKER.defaultLookup().findOrThrow("connect"),
            FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            ErrnoState.captureCallState());

    private static final MethodHandle ACCEPT_HANDLE = LINKER.downcallHandle(
            LINKER.defaultLookup().findOrThrow("accept"),
            FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ErrnoState.captureCallState());

    private static final MethodHandle SENDTO_HANDLE = LINKER.downcallHandle(
            LINKER.defaultLookup().findOrThrow("sendto"),
            FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            ErrnoState.captureCallState());

    private static final MethodHandle RECVFROM_HANDLE = LINKER.downcallHandle(
            LINKER.defaultLookup().findOrThrow("recvfrom"),
            FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            ErrnoState.captureCallState());

    private SocketIO() {
    }

    /**
     * Returns {@code true} if the packed connect result indicates the connection completed.
     *
     * @param packedResultAndErrno a bit-packed result from {@link #connect}
     * @return {@code true} if the socket is now connected
     */
    public static boolean connectIsConnected(final long packedResultAndErrno) {
        return ErrnoState.unpackResult(packedResultAndErrno) == 0;
    }

    /**
     * Returns {@code true} if the packed connect result indicates a non-blocking connection
     * is in progress ({@code EINPROGRESS}).
     *
     * @param packedResultAndErrno a bit-packed result from {@link #connect}
     * @return {@code true} if the connection is pending and the caller should wait for writability
     */
    public static boolean connectIsInProgress(final long packedResultAndErrno) {
        return ErrnoState.unpackResult(packedResultAndErrno) < 0
                && ErrnoState.unpackErrno(packedResultAndErrno) == Errno.EINPROGRESS();
    }

    /**
     * Returns {@code true} if the packed connect result indicates the call was interrupted
     * by a signal ({@code EINTR}) and should be retried.
     *
     * @param packedResultAndErrno a bit-packed result from {@link #connect}
     * @return {@code true} if the call should be retried immediately
     */
    public static boolean connectIsInterrupted(final long packedResultAndErrno) {
        return ErrnoState.unpackResult(packedResultAndErrno) < 0
                && ErrnoState.unpackErrno(packedResultAndErrno) == Errno.EINTR();
    }

    /**
     * Returns {@code true} if the packed accept result indicates the operation would block
     * ({@code EAGAIN} or {@code EWOULDBLOCK}).
     *
     * @param packedResultAndErrno a bit-packed result from {@link #accept}
     * @return {@code true} if no connections are pending
     */
    public static boolean acceptWouldBlock(final long packedResultAndErrno) {
        final int errno = ErrnoState.unpackErrno(packedResultAndErrno);
        return ErrnoState.unpackResult(packedResultAndErrno) < 0
                && (errno == Errno.EAGAIN() || errno == Errno.EWOULDBLOCK());
    }

    /**
     * Returns {@code true} if the packed accept result indicates the call was interrupted
     * by a signal ({@code EINTR}) and should be retried.
     *
     * @param packedResultAndErrno a bit-packed result from {@link #accept}
     * @return {@code true} if the call should be retried immediately
     */
    public static boolean acceptIsInterrupted(final long packedResultAndErrno) {
        return ErrnoState.unpackResult(packedResultAndErrno) < 0
                && ErrnoState.unpackErrno(packedResultAndErrno) == Errno.EINTR();
    }

    /**
     * Returns {@code true} if the packed accept result contains a valid new socket fd.
     *
     * @param packedResultAndErrno a bit-packed result from {@link #accept}
     * @return {@code true} if a connection was successfully accepted
     */
    public static boolean acceptIsSuccess(final long packedResultAndErrno) {
        return ErrnoState.unpackResult(packedResultAndErrno) >= 0;
    }

    /**
     * Returns {@code true} if the packed sendto result indicates the operation would block
     * ({@code EAGAIN} or {@code EWOULDBLOCK}).
     *
     * @param packedResultAndErrno a bit-packed result from {@link #sendto}
     * @return {@code true} if the caller should retry when the fd becomes writable
     */
    public static boolean sendWouldBlock(final long packedResultAndErrno) {
        final int errno = ErrnoState.unpackErrno(packedResultAndErrno);
        return ErrnoState.unpackResult(packedResultAndErrno) < 0
                && (errno == Errno.EAGAIN() || errno == Errno.EWOULDBLOCK());
    }

    /**
     * Returns {@code true} if the packed recvfrom result indicates the operation would block
     * ({@code EAGAIN} or {@code EWOULDBLOCK}).
     *
     * @param packedResultAndErrno a bit-packed result from {@link #recvfrom}
     * @return {@code true} if the caller should retry when the fd becomes readable
     */
    public static boolean recvWouldBlock(final long packedResultAndErrno) {
        final int errno = ErrnoState.unpackErrno(packedResultAndErrno);
        return ErrnoState.unpackResult(packedResultAndErrno) < 0
                && (errno == Errno.EAGAIN() || errno == Errno.EWOULDBLOCK());
    }

    /**
     * Returns {@code true} if the packed recvfrom result indicates end-of-file.
     *
     * @param packedResultAndErrno a bit-packed result from {@link #recvfrom}
     * @return {@code true} if the peer closed the connection
     */
    public static boolean recvIsEof(final long packedResultAndErrno) {
        return ErrnoState.unpackResult(packedResultAndErrno) == 0;
    }

    /**
     * Initiates a connection on a socket with atomic errno capture.
     *
     * @param fd the socket file descriptor
     * @param addr the destination address ({@code struct sockaddr})
     * @param addrlen the size of the address structure in bytes
     * @param capturedState pre-allocated segment sized to {@link ErrnoState#layout()}
     * @return a bit-packed {@code long} with connect result (upper 32) and errno (lower 32)
     * @throws NativeTransportException if the downcall handle invocation fails
     */
    public static long connect(final int fd, final MemorySegment addr, final int addrlen,
                               final MemorySegment capturedState) {
        try {
            final int result = (int) CONNECT_HANDLE.invokeExact(capturedState, fd, addr, addrlen);
            return ErrnoState.pack(result, ErrnoState.extractErrno(capturedState));
        } catch (final Throwable t) {
            throw new NativeTransportException("connect", t);
        }
    }

    /**
     * Accepts a connection on a listening socket with atomic errno capture.
     *
     * @param sockfd the listening socket file descriptor
     * @param addr optional buffer to receive the peer address (may be {@code NULL})
     * @param addrlen optional pointer to the address length (may be {@code NULL})
     * @param capturedState pre-allocated segment sized to {@link ErrnoState#layout()}
     * @return a bit-packed {@code long} with the new fd (upper 32) and errno (lower 32)
     * @throws NativeTransportException if the downcall handle invocation fails
     */
    public static long accept(final int sockfd, final MemorySegment addr,
                              final MemorySegment addrlen, final MemorySegment capturedState) {
        try {
            final int fd = (int) ACCEPT_HANDLE.invokeExact(capturedState, sockfd, addr, addrlen);
            return ErrnoState.pack(fd, ErrnoState.extractErrno(capturedState));
        } catch (final Throwable t) {
            throw new NativeTransportException("accept", t);
        }
    }

    /**
     * Sends data to a specific destination address with atomic errno capture.
     *
     * @param fd the socket file descriptor
     * @param buf the buffer to send from
     * @param len the number of bytes to send
     * @param flags message flags (e.g. {@code MSG_DONTWAIT})
     * @param addr the destination address ({@code struct sockaddr})
     * @param addrlen the size of the address structure in bytes
     * @param capturedState pre-allocated segment sized to {@link ErrnoState#layout()}
     * @return a bit-packed {@code long} with bytes sent (upper 32) and errno (lower 32)
     * @throws NativeTransportException if the downcall handle invocation fails
     */
    public static long sendto(final int fd, final MemorySegment buf, final long len,
                              final int flags, final MemorySegment addr, final int addrlen,
                              final MemorySegment capturedState) {
        try {
            final long sent = (long) SENDTO_HANDLE.invokeExact(
                    capturedState, fd, buf, len, flags, addr, addrlen);
            return ErrnoState.pack(sent, ErrnoState.extractErrno(capturedState));
        } catch (final Throwable t) {
            throw new NativeTransportException("sendto", t);
        }
    }

    /**
     * Receives data and captures the sender's address with atomic errno capture.
     *
     * @param fd the socket file descriptor
     * @param buf the buffer to receive into
     * @param len the maximum number of bytes to receive
     * @param flags message flags (e.g. {@code MSG_DONTWAIT})
     * @param addr optional buffer to receive the sender address (may be {@code NULL})
     * @param addrlen optional pointer to the address length (may be {@code NULL})
     * @param capturedState pre-allocated segment sized to {@link ErrnoState#layout()}
     * @return a bit-packed {@code long} with bytes received (upper 32) and errno (lower 32)
     * @throws NativeTransportException if the downcall handle invocation fails
     */
    public static long recvfrom(final int fd, final MemorySegment buf, final long len,
                                final int flags, final MemorySegment addr,
                                final MemorySegment addrlen, final MemorySegment capturedState) {
        try {
            final long received = (long) RECVFROM_HANDLE.invokeExact(
                    capturedState, fd, buf, len, flags, addr, addrlen);
            return ErrnoState.pack(received, ErrnoState.extractErrno(capturedState));
        } catch (final Throwable t) {
            throw new NativeTransportException("recvfrom", t);
        }
    }

    /**
     * Creates a new socket. Does not capture errno.
     *
     * @param domain the address family (e.g. {@code AF_INET}, {@code AF_INET6})
     * @param type the socket type (e.g. {@code SOCK_STREAM}, {@code SOCK_DGRAM})
     * @param protocol the protocol (typically 0)
     * @return the new socket fd, or -1 on error
     */
    public static int socket(final int domain, final int type, final int protocol) {
        return BsdSocket.socket(domain, type, protocol);
    }

    /**
     * Binds a socket to an address. Does not capture errno.
     *
     * @param fd the socket file descriptor
     * @param addr the address to bind to ({@code struct sockaddr})
     * @param addrlen the size of the address structure in bytes
     * @return 0 on success, -1 on error
     */
    public static int bind(final int fd, final MemorySegment addr, final int addrlen) {
        return BsdSocket.bind(fd, addr, addrlen);
    }

    /**
     * Marks a socket as passive (accepting connections). Does not capture errno.
     *
     * @param fd the socket file descriptor
     * @param backlog the maximum pending connection queue length
     * @return 0 on success, -1 on error
     */
    public static int listen(final int fd, final int backlog) {
        return BsdSocket.listen(fd, backlog);
    }

    /**
     * Shuts down part or all of a full-duplex connection. Does not capture errno.
     *
     * @param fd the socket file descriptor
     * @param how one of {@code SHUT_RD}, {@code SHUT_WR}, or {@code SHUT_RDWR}
     * @return 0 on success, -1 on error
     */
    public static int shutdown(final int fd, final int how) {
        return BsdSocket.shutdown(fd, how);
    }
}
