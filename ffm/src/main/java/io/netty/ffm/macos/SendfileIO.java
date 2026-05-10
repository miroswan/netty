package io.netty.ffm.macos;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.macos.generated.BsdSocket;
import io.netty.ffm.macos.generated.Errno;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * FFM wrapper for macOS {@code sendfile(2)} with errno capture. On macOS, sendfile
 * transfers data from a file descriptor to a socket without copying through userspace.
 *
 * <p>macOS sendfile signature differs from Linux:
 * <pre>{@code
 * int sendfile(int fd, int s, off_t offset, off_t *len, struct sf_hdtr *hdtr, int flags);
 * }</pre>
 *
 * <p>Where:
 * <ul>
 *   <li>{@code fd} — the source file descriptor</li>
 *   <li>{@code s} — the destination socket descriptor</li>
 *   <li>{@code offset} — byte offset in the file to start sending from</li>
 *   <li>{@code len} — in/out: on input the number of bytes to send, on output the
 *       number of bytes actually sent</li>
 *   <li>{@code hdtr} — optional headers/trailers (NULL for us)</li>
 *   <li>{@code flags} — reserved, must be 0</li>
 * </ul>
 *
 * <p>Returns 0 on success, -1 on error. On EAGAIN, partial bytes may still have been
 * sent — always read the {@code len} out-parameter.
 */
public final class SendfileIO {

    private static final MethodHandle SENDFILE_HANDLE = Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().findOrThrow("sendfile"),
            FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
            ErrnoState.captureCallState());

    private SendfileIO() {
    }

    /**
     * Transfers bytes from a file descriptor to a socket using zero-copy sendfile.
     *
     * <p>The caller must write the desired byte count into {@code lenBuf} before calling,
     * and read the actual bytes sent from {@code lenBuf} after the call returns — even on
     * EAGAIN, partial data may have been transferred.
     *
     * @param fileFd the source file descriptor
     * @param socketFd the destination socket descriptor
     * @param offset the byte offset in the file to start from
     * @param lenBuf a pre-allocated 8-byte segment: write desired count before call,
     *               read actual sent bytes after call
     * @param capturedState pre-allocated segment for errno capture
     * @return a bit-packed {@code long} with sendfile result (0 success, -1 error) and errno
     */
    public static long sendfile(final int fileFd, final int socketFd, final long offset,
                                final MemorySegment lenBuf, final MemorySegment capturedState) {
        try {
            final int result = (int) SENDFILE_HANDLE.invokeExact(
                    capturedState, fileFd, socketFd, offset, lenBuf, MemorySegment.NULL, 0);
            return ErrnoState.pack(result, ErrnoState.extractErrno(capturedState));
        } catch (final Throwable t) {
            throw new io.netty.ffm.NativeTransportException("sendfile", t);
        }
    }

    /**
     * Returns {@code true} if the packed sendfile result indicates EAGAIN (socket buffer
     * full). The caller should still check {@code lenBuf} for partial transfer.
     *
     * @param packedResultAndErrno the result from {@link #sendfile}
     * @return {@code true} if the call would block
     */
    public static boolean wouldBlock(final long packedResultAndErrno) {
        return ErrnoState.unpackResult(packedResultAndErrno) < 0
                && ErrnoState.unpackErrno(packedResultAndErrno) == Errno.EAGAIN();
    }

    /**
     * Returns {@code true} if the packed sendfile result indicates success (all requested
     * bytes were sent).
     *
     * @param packedResultAndErrno the result from {@link #sendfile}
     * @return {@code true} if the call completed without error
     */
    public static boolean isSuccess(final long packedResultAndErrno) {
        return ErrnoState.unpackResult(packedResultAndErrno) == 0;
    }
}
