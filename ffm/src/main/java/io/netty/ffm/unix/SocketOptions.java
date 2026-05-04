package io.netty.ffm.unix;

import io.netty.ffm.NativeTransportException;
import io.netty.ffm.unix.generated.BsdSocket;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Socket option and file control operations. {@code setsockopt} and {@code getsockopt}
 * delegate directly to the jextract-generated bindings. {@code fcntl} uses custom
 * downcall handles with {@link Linker.Option#firstVariadicArg(int)} to correctly handle
 * the variadic calling convention on all architectures.
 */
public final class SocketOptions {

    private static final Linker LINKER = Linker.nativeLinker();

    private static final MethodHandle FCNTL_INT_HANDLE = LINKER.downcallHandle(
            LINKER.defaultLookup().findOrThrow("fcntl"),
            FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
            Linker.Option.firstVariadicArg(2));

    private static final MethodHandle FCNTL_VOID_HANDLE = LINKER.downcallHandle(
            LINKER.defaultLookup().findOrThrow("fcntl"),
            FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
            Linker.Option.firstVariadicArg(2));

    private SocketOptions() {
    }

    /**
     * Sets a socket option.
     *
     * @param fd the socket file descriptor
     * @param level the protocol level (e.g. {@code SOL_SOCKET}, {@code IPPROTO_TCP})
     * @param optname the option name (e.g. {@code SO_REUSEADDR}, {@code TCP_NODELAY})
     * @param optval a segment containing the option value
     * @param optlen the size of the option value in bytes
     * @return 0 on success, -1 on error
     */
    public static int setsockopt(final int fd, final int level, final int optname,
                                 final MemorySegment optval, final int optlen) {
        return BsdSocket.setsockopt(fd, level, optname, optval, optlen);
    }

    /**
     * Gets a socket option.
     *
     * @param fd the socket file descriptor
     * @param level the protocol level (e.g. {@code SOL_SOCKET}, {@code IPPROTO_TCP})
     * @param optname the option name (e.g. {@code SO_ERROR}, {@code SO_KEEPALIVE})
     * @param optval a segment to receive the option value
     * @param optlen a segment containing the size of {@code optval}, updated on return
     * @return 0 on success, -1 on error
     */
    public static int getsockopt(final int fd, final int level, final int optname,
                                 final MemorySegment optval, final MemorySegment optlen) {
        return BsdSocket.getsockopt(fd, level, optname, optval, optlen);
    }

    /**
     * Performs a file control operation with no additional argument. Typically used
     * with {@code F_GETFL} to retrieve file status flags.
     *
     * @param fd the file descriptor
     * @param cmd the control command (e.g. {@code F_GETFL})
     * @return the command-specific result, or -1 on error
     * @throws NativeTransportException if the downcall handle invocation fails
     */
    public static int fcntl(final int fd, final int cmd) {
        try {
            return (int) FCNTL_VOID_HANDLE.invokeExact(fd, cmd);
        } catch (final Throwable t) {
            throw new NativeTransportException("fcntl", t);
        }
    }

    /**
     * Performs a file control operation with an integer argument. Typically used
     * with {@code F_SETFL} to set file status flags (e.g. {@code O_NONBLOCK}).
     *
     * @param fd the file descriptor
     * @param cmd the control command (e.g. {@code F_SETFL})
     * @param arg the command argument (e.g. flags to set)
     * @return the command-specific result, or -1 on error
     * @throws NativeTransportException if the downcall handle invocation fails
     */
    public static int fcntl(final int fd, final int cmd, final int arg) {
        try {
            return (int) FCNTL_INT_HANDLE.invokeExact(fd, cmd, arg);
        } catch (final Throwable t) {
            throw new NativeTransportException("fcntl", t);
        }
    }
}
