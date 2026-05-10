package io.netty.ffm.macos;

import io.netty.ffm.macos.generated.BsdSocket;
import io.netty.ffm.macos.generated.in6_addr;
import io.netty.ffm.macos.generated.in_addr;
import io.netty.ffm.macos.generated.sockaddr_in;
import io.netty.ffm.macos.generated.sockaddr_in6;
import io.netty.ffm.macos.generated.sockaddr_un;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * Converts between Java {@link InetSocketAddress} and macOS native
 * {@code sockaddr_in} / {@code sockaddr_in6} memory segments.
 *
 * <p>All allocating methods take a {@link SegmentAllocator} so the caller controls
 * memory lifetime. Port and address values are written in network byte order as
 * required by the BSD socket API.
 */
public final class SockaddrUtil {

    private SockaddrUtil() {
    }

    /**
     * Allocates and populates a {@code sockaddr_in} or {@code sockaddr_in6} from a Java
     * socket address. The struct type is determined by the address family.
     *
     * @param allocator the allocator for the native struct
     * @param address the Java socket address to convert
     * @return a segment containing the populated sockaddr struct
     * @throws IllegalArgumentException if the address is not IPv4 or IPv6
     */
    public static MemorySegment toSockaddr(final SegmentAllocator allocator,
                                           final InetSocketAddress address) {
        final InetAddress inet = address.getAddress();
        if (inet instanceof Inet4Address) {
            return toSockaddrIn(allocator, address.getPort(), inet.getAddress());
        } else if (inet instanceof Inet6Address inet6) {
            return toSockaddrIn6(allocator, address.getPort(), inet6);
        }
        throw new IllegalArgumentException("Unsupported address type: " + inet.getClass());
    }

    /**
     * Returns the byte size of the native sockaddr struct for the given address.
     *
     * @param address the Java socket address
     * @return the size in bytes of the corresponding native struct
     * @throws IllegalArgumentException if the address is not IPv4 or IPv6
     */
    public static int sockaddrSize(final InetSocketAddress address) {
        final InetAddress inet = address.getAddress();
        if (inet instanceof Inet4Address) {
            return (int) sockaddr_in.sizeof();
        } else if (inet instanceof Inet6Address) {
            return (int) sockaddr_in6.sizeof();
        }
        throw new IllegalArgumentException("Unsupported address type: " + inet.getClass());
    }

    /**
     * Reads a native {@code sockaddr_in} or {@code sockaddr_in6} and converts it to a
     * Java {@link InetSocketAddress}. The address family is read from the {@code sin_family}
     * field at byte offset 1 (after the {@code sin_len} field on macOS/BSD).
     *
     * @param sockaddr a segment containing a populated sockaddr struct
     * @return the corresponding Java socket address
     * @throws IllegalArgumentException if the address family is not {@code AF_INET} or {@code AF_INET6}
     */
    public static InetSocketAddress fromSockaddr(final MemorySegment sockaddr) {
        final byte family = sockaddr.get(ValueLayout.JAVA_BYTE, 1);
        if (family == (byte) BsdSocket.AF_INET()) {
            return fromSockaddrIn(sockaddr);
        } else if (family == (byte) BsdSocket.AF_INET6()) {
            return fromSockaddrIn6(sockaddr);
        }
        throw new IllegalArgumentException("Unsupported address family: " + (family & 0xFF));
    }

    /**
     * Allocates and populates a {@code sockaddr_un} from a Java
     * {@link UnixDomainSocketAddress}. The path is null-terminated and must
     * fit within the 104-byte {@code sun_path} field.
     *
     * @param allocator the allocator for the native struct
     * @param address the Unix domain socket address
     * @return a segment containing the populated sockaddr_un struct
     * @throws IllegalArgumentException if the path exceeds 103 bytes (plus null terminator)
     */
    public static MemorySegment toSockaddrUn(final SegmentAllocator allocator,
                                             final UnixDomainSocketAddress address) {
        final byte[] pathBytes = address.getPath().toString().getBytes(StandardCharsets.UTF_8);
        if (pathBytes.length > 103) {
            throw new IllegalArgumentException(
                    "Unix domain socket path too long: " + pathBytes.length + " bytes (max 103)");
        }
        final MemorySegment sa = sockaddr_un.allocate(allocator);
        sockaddr_un.sun_len(sa, (byte) (2 + pathBytes.length + 1));
        sockaddr_un.sun_family(sa, (byte) BsdSocket.AF_UNIX());
        final long pathOffset = sockaddr_un.sun_path$offset();
        MemorySegment.copy(pathBytes, 0, sa, ValueLayout.JAVA_BYTE, pathOffset, pathBytes.length);
        sa.set(ValueLayout.JAVA_BYTE, pathOffset + pathBytes.length, (byte) 0);
        return sa;
    }

    /**
     * Returns the size in bytes of a populated {@code sockaddr_un} for the given address.
     *
     * @param address the Unix domain socket address
     * @return the size of the sockaddr_un including the path
     */
    public static int sockaddrUnSize(final UnixDomainSocketAddress address) {
        final byte[] pathBytes = address.getPath().toString().getBytes(StandardCharsets.UTF_8);
        return 2 + pathBytes.length + 1;
    }

    /**
     * Reads a native {@code sockaddr_un} and converts it to a Java
     * {@link UnixDomainSocketAddress}.
     *
     * @param sockaddr a segment containing a populated sockaddr_un struct
     * @return the corresponding Java Unix domain socket address
     */
    public static UnixDomainSocketAddress fromSockaddrUn(final MemorySegment sockaddr) {
        final long pathOffset = sockaddr_un.sun_path$offset();
        final int maxLen = 104;
        int len = 0;
        while (len < maxLen && sockaddr.get(ValueLayout.JAVA_BYTE, pathOffset + len) != 0) {
            len++;
        }
        final byte[] pathBytes = new byte[len];
        MemorySegment.copy(sockaddr, ValueLayout.JAVA_BYTE, pathOffset, pathBytes, 0, len);
        return UnixDomainSocketAddress.of(new String(pathBytes, StandardCharsets.UTF_8));
    }

    private static MemorySegment toSockaddrIn(final SegmentAllocator allocator, final int port,
                                              final byte[] addrBytes) {
        final MemorySegment sa = sockaddr_in.allocate(allocator);
        sockaddr_in.sin_len(sa, (byte) sockaddr_in.sizeof());
        sockaddr_in.sin_family(sa, (byte) BsdSocket.AF_INET());
        sockaddr_in.sin_port(sa, htons((short) port));
        final MemorySegment sinAddr = sockaddr_in.sin_addr(sa);
        MemorySegment.copy(addrBytes, 0, sinAddr, ValueLayout.JAVA_BYTE, 0, 4);
        return sa;
    }

    private static MemorySegment toSockaddrIn6(final SegmentAllocator allocator, final int port,
                                               final Inet6Address inet6) {
        final MemorySegment sa = sockaddr_in6.allocate(allocator);
        sockaddr_in6.sin6_len(sa, (byte) sockaddr_in6.sizeof());
        sockaddr_in6.sin6_family(sa, (byte) BsdSocket.AF_INET6());
        sockaddr_in6.sin6_port(sa, htons((short) port));
        sockaddr_in6.sin6_flowinfo(sa, 0);
        sockaddr_in6.sin6_scope_id(sa, inet6.getScopeId());
        final MemorySegment sin6Addr = sockaddr_in6.sin6_addr(sa);
        MemorySegment.copy(inet6.getAddress(), 0, sin6Addr, ValueLayout.JAVA_BYTE, 0, 16);
        return sa;
    }

    private static InetSocketAddress fromSockaddrIn(final MemorySegment sa) {
        final int port = Short.toUnsignedInt(ntohs(sockaddr_in.sin_port(sa)));
        final MemorySegment sinAddr = sockaddr_in.sin_addr(sa);
        final byte[] addrBytes = new byte[4];
        MemorySegment.copy(sinAddr, ValueLayout.JAVA_BYTE, 0, addrBytes, 0, 4);
        try {
            return new InetSocketAddress(InetAddress.getByAddress(addrBytes), port);
        } catch (final UnknownHostException e) {
            throw new AssertionError("4-byte address should never fail", e);
        }
    }

    private static InetSocketAddress fromSockaddrIn6(final MemorySegment sa) {
        final int port = Short.toUnsignedInt(ntohs(sockaddr_in6.sin6_port(sa)));
        final int scopeId = sockaddr_in6.sin6_scope_id(sa);
        final MemorySegment sin6Addr = sockaddr_in6.sin6_addr(sa);
        final byte[] addrBytes = new byte[16];
        MemorySegment.copy(sin6Addr, ValueLayout.JAVA_BYTE, 0, addrBytes, 0, 16);
        try {
            return new InetSocketAddress(Inet6Address.getByAddress(null, addrBytes, scopeId), port);
        } catch (final UnknownHostException e) {
            throw new AssertionError("16-byte address should never fail", e);
        }
    }

    private static short htons(final short value) {
        return Short.reverseBytes(value);
    }

    private static short ntohs(final short value) {
        return Short.reverseBytes(value);
    }
}
