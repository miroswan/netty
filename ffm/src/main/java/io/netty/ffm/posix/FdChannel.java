package io.netty.ffm.posix;

import java.lang.foreign.MemorySegment;

/**
 * Thin wrapper around a POSIX file descriptor that provides read, write, and close
 * operations via {@link FileIO}. The caller is responsible for providing a pre-allocated
 * {@code capturedState} segment for zero-allocation I/O on the hot path.
 *
 * <p>Implements {@link AutoCloseable} so it can be used in try-with-resources blocks.
 * The caller must ensure {@link #close()} is called exactly once and that no I/O is
 * attempted after closing.
 */
public final class FdChannel implements AutoCloseable {

    private final int fd;

    /**
     * Wraps an existing open file descriptor.
     *
     * @param fd a valid, open file descriptor
     */
    public FdChannel(final int fd) {
        this.fd = fd;
    }

    /**
     * Returns the underlying file descriptor value.
     *
     * @return the raw fd
     */
    public int fd() {
        return fd;
    }

    /**
     * Reads up to {@code nbyte} bytes from this fd into the buffer.
     *
     * @param buf the buffer to read into
     * @param nbyte the maximum number of bytes to read
     * @param capturedState pre-allocated segment sized to {@link io.netty.ffm.ErrnoState#layout()}
     * @return a bit-packed {@code long} with bytes read and errno
     * @see FileIO#read(int, MemorySegment, long, MemorySegment)
     */
    public long read(final MemorySegment buf, final long nbyte, final MemorySegment capturedState) {
        return FileIO.read(fd, buf, nbyte, capturedState);
    }

    /**
     * Writes up to {@code nbyte} bytes from the buffer to this fd.
     *
     * @param buf the buffer to write from
     * @param nbyte the number of bytes to write
     * @param capturedState pre-allocated segment sized to {@link io.netty.ffm.ErrnoState#layout()}
     * @return a bit-packed {@code long} with bytes written and errno
     * @see FileIO#write(int, MemorySegment, long, MemorySegment)
     */
    public long write(final MemorySegment buf, final long nbyte, final MemorySegment capturedState) {
        return FileIO.write(fd, buf, nbyte, capturedState);
    }

    /**
     * Writes multiple buffers to this fd using vectored I/O.
     *
     * @param iov a {@code struct iovec} array segment
     * @param iovcnt the number of iovec entries
     * @param capturedState pre-allocated segment sized to {@link io.netty.ffm.ErrnoState#layout()}
     * @return a bit-packed {@code long} with bytes written and errno
     * @see FileIO#writev(int, MemorySegment, int, MemorySegment)
     */
    public long writev(final MemorySegment iov, final int iovcnt, final MemorySegment capturedState) {
        return FileIO.writev(fd, iov, iovcnt, capturedState);
    }

    /**
     * Reads from this fd into multiple buffers using vectored I/O.
     *
     * @param iov a {@code struct iovec} array segment
     * @param iovcnt the number of iovec entries
     * @param capturedState pre-allocated segment sized to {@link io.netty.ffm.ErrnoState#layout()}
     * @return a bit-packed {@code long} with bytes read and errno
     * @see FileIO#readv(int, MemorySegment, int, MemorySegment)
     */
    public long readv(final MemorySegment iov, final int iovcnt, final MemorySegment capturedState) {
        return FileIO.readv(fd, iov, iovcnt, capturedState);
    }

    /**
     * Closes this file descriptor. Must be called exactly once.
     */
    @Override
    public void close() {
        FileIO.close(fd);
    }
}
