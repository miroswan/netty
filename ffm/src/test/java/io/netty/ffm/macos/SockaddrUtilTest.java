package io.netty.ffm.macos;

import io.netty.ffm.macos.generated.BsdSocket;
import io.netty.ffm.macos.generated.sockaddr_in;
import io.netty.ffm.macos.generated.sockaddr_in6;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SockaddrUtilTest {

    @Test
    void ipv4RoundTrip() {
        try (Arena arena = Arena.ofConfined()) {
            final InetSocketAddress original = new InetSocketAddress("127.0.0.1", 8080);
            final MemorySegment sa = SockaddrUtil.toSockaddr(arena, original);
            final InetSocketAddress recovered = SockaddrUtil.fromSockaddr(sa);

            assertEquals(original.getPort(), recovered.getPort());
            assertEquals(original.getAddress(), recovered.getAddress());
        }
    }

    @Test
    void ipv4WildcardAddress() {
        try (Arena arena = Arena.ofConfined()) {
            final InetSocketAddress original = new InetSocketAddress("0.0.0.0", 0);
            final MemorySegment sa = SockaddrUtil.toSockaddr(arena, original);
            final InetSocketAddress recovered = SockaddrUtil.fromSockaddr(sa);

            assertEquals(0, recovered.getPort());
            assertEquals(original.getAddress(), recovered.getAddress());
        }
    }

    @Test
    void ipv4HighPort() {
        try (Arena arena = Arena.ofConfined()) {
            final InetSocketAddress original = new InetSocketAddress("10.0.0.1", 65535);
            final MemorySegment sa = SockaddrUtil.toSockaddr(arena, original);
            final InetSocketAddress recovered = SockaddrUtil.fromSockaddr(sa);

            assertEquals(65535, recovered.getPort());
            assertEquals(original.getAddress(), recovered.getAddress());
        }
    }

    @Test
    void ipv6RoundTrip() throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            final InetSocketAddress original = new InetSocketAddress("::1", 9090);
            final MemorySegment sa = SockaddrUtil.toSockaddr(arena, original);
            final InetSocketAddress recovered = SockaddrUtil.fromSockaddr(sa);

            assertEquals(original.getPort(), recovered.getPort());
            assertInstanceOf(Inet6Address.class, recovered.getAddress());
            assertEquals(original.getAddress(), recovered.getAddress());
        }
    }

    @Test
    void ipv6WithScopeId() throws Exception {
        try (Arena arena = Arena.ofConfined()) {
            final Inet6Address addr = Inet6Address.getByAddress(
                    null, InetAddress.getByName("fe80::1").getAddress(), 5);
            final InetSocketAddress original = new InetSocketAddress(addr, 443);
            final MemorySegment sa = SockaddrUtil.toSockaddr(arena, original);
            final InetSocketAddress recovered = SockaddrUtil.fromSockaddr(sa);

            assertEquals(443, recovered.getPort());
            assertInstanceOf(Inet6Address.class, recovered.getAddress());
            assertEquals(5, ((Inet6Address) recovered.getAddress()).getScopeId());
        }
    }

    @Test
    void sockaddrSizeIpv4() {
        final InetSocketAddress addr = new InetSocketAddress("127.0.0.1", 80);
        assertEquals((int) sockaddr_in.sizeof(), SockaddrUtil.sockaddrSize(addr));
    }

    @Test
    void sockaddrSizeIpv6() {
        final InetSocketAddress addr = new InetSocketAddress("::1", 80);
        assertEquals((int) sockaddr_in6.sizeof(), SockaddrUtil.sockaddrSize(addr));
    }

    @Test
    void ipv4StructFieldsCorrect() {
        try (Arena arena = Arena.ofConfined()) {
            final InetSocketAddress address = new InetSocketAddress("192.168.1.100", 8080);
            final MemorySegment sa = SockaddrUtil.toSockaddr(arena, address);

            assertEquals((byte) sockaddr_in.sizeof(), sockaddr_in.sin_len(sa));
            assertEquals((byte) BsdSocket.AF_INET(), sockaddr_in.sin_family(sa));
        }
    }

    @Test
    void bindWithConvertedAddress() {
        try (Arena arena = Arena.ofConfined()) {
            final int fd = SocketIO.socket(BsdSocket.AF_INET(), BsdSocket.SOCK_STREAM(), 0);

            final InetSocketAddress address = new InetSocketAddress("127.0.0.1", 0);
            final MemorySegment sa = SockaddrUtil.toSockaddr(arena, address);
            final int result = SocketIO.bind(fd, sa, SockaddrUtil.sockaddrSize(address));

            assertEquals(0, result);

            final MemorySegment boundAddr = sockaddr_in.allocate(arena);
            final MemorySegment addrLen = arena.allocate(ValueLayout.JAVA_INT);
            addrLen.set(ValueLayout.JAVA_INT, 0, (int) sockaddr_in.sizeof());
            io.netty.ffm.macos.generated.BsdSocket.getsockname(fd, boundAddr, addrLen);

            final InetSocketAddress bound = SockaddrUtil.fromSockaddr(boundAddr);
            assertEquals(InetAddress.getByName("127.0.0.1"), bound.getAddress());
            assertTrue(bound.getPort() > 0);

            io.netty.ffm.posix.FileIO.close(fd);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }
}
