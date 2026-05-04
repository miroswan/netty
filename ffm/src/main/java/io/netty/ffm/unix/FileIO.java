package io.netty.ffm.unix;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.NativeTransportException;
import io.netty.ffm.unix.generated.Errno;
import io.netty.ffm.unix.generated.Posix;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Zero-allocation file I/O wrappers with atomic errno capture via
 * {@link Linker.Option#captureCallState(String...)}. All methods return a bit-packed
 * {@code long} containing the syscall result and errno, decodable via
 * {@link ErrnoState#unpackResult} and {@link ErrnoState#unpackErrno}.
 *
 * <p>The caller must pre-allocate a {@code capturedState} segment from
 * {@link ErrnoState#layout()} on a long-lived arena (typically one per event loop thread)
 * and pass it to every call. This avoids per-call arena allocation on the hot path.
 */
public final class FileIO {

    private static final Linker LINKER = Linker.nativeLinker();

    private static final MethodHandle READ_HANDLE = LINKER.downcallHandle(
            LINKER.defaultLookup().findOrThrow("read"),
            FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
            ErrnoState.captureCallState());

    private static final MethodHandle WRITE_HANDLE = LINKER.downcallHandle(
            LINKER.defaultLookup().findOrThrow("write"),
            FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
            ErrnoState.captureCallState());

    private static final MethodHandle WRITEV_HANDLE = LINKER.downcallHandle(
            LINKER.defaultLookup().findOrThrow("writev"),
            FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            ErrnoState.captureCallState());

    private static final MethodHandle READV_HANDLE = LINKER.downcallHandle(
            LINKER.defaultLookup().findOrThrow("readv"),
            FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            ErrnoState.captureCallState());

    private FileIO() {
    }

    /**
     * Returns {@code true} if the packed result indicates the operation would block
     * ({@code EAGAIN} or {@code EWOULDBLOCK}).
     *
     * @param packedResultAndErrno a bit-packed result from any I/O method in this class
     * @return {@code true} if the caller should retry when the fd becomes ready
     */
    public static boolean wouldBlock(final long packedResultAndErrno) {
        final int errno = ErrnoState.unpackErrno(packed);
        return ErrnoState.unpackResult(packed) < 0
                && (errno == Errno.EAGAIN() || errno == Errno.EWOULDBLOCK());
    }

    /**
     * Returns {@code true} if the packed result indicates end-of-file (zero bytes read).
     *
     * @param packedResultAndErrno a bit-packed result from {@link #read} or {@link #readv}
     * @return {@code true} if the peer closed the connection
     */
    public static boolean isEof(final long packedResultAndErrno) {
        return ErrnoState.unpackResult(packed) == 0;
    }

    /**
     * Returns {@code true} if the packed result indicates the call was interrupted by a
     * signal ({@code EINTR}) and should be retried.
     *
     * @param packedResultAndErrno a bit-packed result from any I/O method in this class
     * @return {@code true} if the call should be retried immediately
     */
    public static boolean isInterrupted(final long packedResultAndErrno) {
        return ErrnoState.unpackResult(packed) < 0 && ErrnoState.unpackErrno(packed) == Errno.EINTR();
    }

    /**
     * Reads up to {@code nbyte} bytes from the file descriptor into the buffer.
     *
     * @param fd the file descriptor to read from
     * @param buf the buffer to read into
     * @param nbyte the maximum number of bytes to read
     * @param capturedState pre-allocated segment sized to {@link ErrnoState#layout()}
     * @return a bit-packed {@code long} with bytes read (upper 32) and errno (lower 32)
     * @throws NativeTransportException if the downcall handle invocation fails
     */
    public static long read(final int fd, final MemorySegment buf, final long nbyte,
                            final MemorySegment capturedState) {
        try {
            final long bytesRead = (long) READ_HANDLE.invokeExact(capturedState, fd, buf, nbyte);
            return ErrnoState.pack(bytesRead, ErrnoState.extractErrno(capturedState));
        } catch (final Throwable t) {
            throw new NativeTransportException("read", t);
        }
    }

    /**
     * Writes up to {@code nbyte} bytes from the buffer to the file descriptor.
     *
     * @param fd the file descriptor to write to
     * @param buf the buffer to write from
     * @param nbyte the number of bytes to write
     * @param capturedState pre-allocated segment sized to {@link ErrnoState#layout()}
     * @return a bit-packed {@code long} with bytes written (upper 32) and errno (lower 32)
     * @throws NativeTransportException if the downcall handle invocation fails
     */
    public static long write(final int fd, final MemorySegment buf, final long nbyte,
                             final MemorySegment capturedState) {
        try {
            final long bytesWritten = (long) WRITE_HANDLE.invokeExact(capturedState, fd, buf, nbyte);
            return ErrnoState.pack(bytesWritten, ErrnoState.extractErrno(capturedState));
        } catch (final Throwable t) {
            throw new NativeTransportException("write", t);
        }
    }

    /**
     * Writes multiple buffers to the file descriptor using vectored I/O.
     *
     * @param fd the file descriptor to write to
     * @param iov a {@code struct iovec} array segment
     * @param iovcnt the number of iovec entries
     * @param capturedState pre-allocated segment sized to {@link ErrnoState#layout()}
     * @return a bit-packed {@code long} with bytes written (upper 32) and errno (lower 32)
     * @throws NativeTransportException if the downcall handle invocation fails
     */
    public static long writev(final int fd, final MemorySegment iov, final int iovcnt,
                              final MemorySegment capturedState) {
        try {
            final long bytesWritten = (long) WRITEV_HANDLE.invokeExact(capturedState, fd, iov, iovcnt);
            return ErrnoState.pack(bytesWritten, ErrnoState.extractErrno(capturedState));
        } catch (final Throwable t) {
            throw new NativeTransportException("writev", t);
        }
    }

    /**
     * Reads from the file descriptor into multiple buffers using vectored I/O.
     *
     * @param fd the file descriptor to read from
     * @param iov a {@code struct iovec} array segment
     * @param iovcnt the number of iovec entries
     * @param capturedState pre-allocated segment sized to {@link ErrnoState#layout()}
     * @return a bit-packed {@code long} with bytes read (upper 32) and errno (lower 32)
     * @throws NativeTransportException if the downcall handle invocation fails
     */
    public static long readv(final int fd, final MemorySegment iov, final int iovcnt,
                             final MemorySegment capturedState) {
        try {
            final long bytesRead = (long) READV_HANDLE.invokeExact(capturedState, fd, iov, iovcnt);
            return ErrnoState.pack(bytesRead, ErrnoState.extractErrno(capturedState));
        } catch (final Throwable t) {
            throw new NativeTransportException("readv", t);
        }
    }

    /**
     * Closes the file descriptor. Does not capture errno.
     *
     * @param fd the file descriptor to close
     * @return 0 on success, -1 on error
     */
    public static int close(final int fd) {
        return Posix.close(fd);
    }

    /**
     * Creates a unidirectional pipe. The read end is stored at index 0 and the write
     * end at index 1 of the provided two-element {@code int} array segment.
     *
     * @param pipefd a segment of at least 8 bytes (two ints)
     * @return 0 on success, -1 on error
     */
    public static int pipe(final MemorySegment pipefd) {
        return Posix.pipe(pipefd);
    }
}
