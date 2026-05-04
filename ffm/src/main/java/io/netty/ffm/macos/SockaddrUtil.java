package io.netty.ffm.macos;

import io.netty.ffm.macos.generated.BsdSocket;
import io.netty.ffm.macos.generated.in6_addr;
import io.netty.ffm.macos.generated.in_addr;
import io.netty.ffm.macos.generated.sockaddr_in;
import io.netty.ffm.macos.generated.sockaddr_in6;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

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
