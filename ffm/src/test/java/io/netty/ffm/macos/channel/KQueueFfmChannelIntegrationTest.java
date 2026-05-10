package io.netty.ffm.macos.channel;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.ffm.macos.KQueueFfmIoHandler;
import io.netty.ffm.macos.generated.BsdSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests that exercise the full FFM channel stack through Netty's
 * Bootstrap/ServerBootstrap infrastructure. Validates that the IoHandler, channels,
 * and pipeline work together end-to-end.
 */
class KQueueFfmChannelIntegrationTest {

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    @BeforeEach
    void setUp() {
        bossGroup = new MultiThreadIoEventLoopGroup(1, KQueueFfmIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(1, KQueueFfmIoHandler.newFactory());
    }

    @AfterEach
    void tearDown() {
        workerGroup.shutdownGracefully().syncUninterruptibly();
        bossGroup.shutdownGracefully().syncUninterruptibly();
    }

    @Test
    void tcpEchoRoundTrip() throws Exception {
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();

        final ServerBootstrap sb = new ServerBootstrap();
        sb.group(bossGroup, workerGroup)
                .channelFactory(KQueueFfmServerSocketChannel::new)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(final Channel ch) {
                        ch.pipeline().addLast(new EchoHandler());
                    }
                });

        final Channel serverChannel = sb.bind(new InetSocketAddress("127.0.0.1", 0))
                .sync().getNow();
        final InetSocketAddress serverAddr =
                (InetSocketAddress) serverChannel.localAddress();

        final Bootstrap cb = new Bootstrap();
        cb.group(workerGroup)
                .channelFactory(eventLoop ->
                        new KQueueFfmSocketChannel(eventLoop, BsdSocket.AF_INET()))
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(final Channel ch) {
                        ch.pipeline().addLast(new CollectorHandler(received));
                    }
                });

        final Channel clientChannel = cb.connect(serverAddr).sync().getNow();

        clientChannel.writeAndFlush(
                Unpooled.copiedBuffer("hello ffm", StandardCharsets.UTF_8)).sync();

        final String echoedBack = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(echoedBack, "Did not receive echo response within timeout");
        assertEquals("hello ffm", echoedBack);

        clientChannel.close().sync();
        serverChannel.close().sync();
    }

    @Test
    void largePayloadTransfer() throws Exception {
        final BlockingQueue<Integer> receivedSizes = new LinkedBlockingQueue<>();

        final ServerBootstrap sb = new ServerBootstrap();
        sb.group(bossGroup, workerGroup)
                .channelFactory(KQueueFfmServerSocketChannel::new)
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(final Channel ch) {
                        ch.pipeline().addLast(new AccumulatingHandler(receivedSizes, 65536));
                    }
                });

        final Channel serverChannel = sb.bind(new InetSocketAddress("127.0.0.1", 0))
                .sync().getNow();
        final InetSocketAddress serverAddr =
                (InetSocketAddress) serverChannel.localAddress();

        final Bootstrap cb = new Bootstrap();
        cb.group(workerGroup)
                .channelFactory(eventLoop ->
                        new KQueueFfmSocketChannel(eventLoop, BsdSocket.AF_INET()))
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(final Channel ch) {
                    }
                });

        final Channel clientChannel = cb.connect(serverAddr).sync().getNow();

        final byte[] data = new byte[65536];
        java.util.Arrays.fill(data, (byte) 'X');
        clientChannel.writeAndFlush(Unpooled.wrappedBuffer(data)).sync();

        final Integer totalReceived = receivedSizes.poll(5, TimeUnit.SECONDS);
        assertNotNull(totalReceived, "Did not receive full payload within timeout");
        assertEquals(65536, totalReceived.intValue());

        clientChannel.close().sync();
        serverChannel.close().sync();
    }

    /**
     * Echo handler: writes back everything it reads.
     */
    private static final class EchoHandler implements ChannelInboundHandler {
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
            ctx.writeAndFlush(msg);
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx,
                                    final Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }

    /**
     * Collects read messages as strings into a blocking queue.
     */
    private static final class CollectorHandler implements ChannelInboundHandler {
        private final BlockingQueue<String> queue;

        CollectorHandler(final BlockingQueue<String> queue) {
            this.queue = queue;
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
            final ByteBuf buf = (ByteBuf) msg;
            queue.offer(buf.toString(StandardCharsets.UTF_8));
            buf.release();
        }
    }

    /**
     * Accumulates bytes until a target is reached, then reports total to a queue.
     */
    private static final class AccumulatingHandler implements ChannelInboundHandler {
        private final BlockingQueue<Integer> queue;
        private final int target;
        private int totalRead;

        AccumulatingHandler(final BlockingQueue<Integer> queue, final int target) {
            this.queue = queue;
            this.target = target;
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
            final ByteBuf buf = (ByteBuf) msg;
            totalRead += buf.readableBytes();
            buf.release();
            if (totalRead >= target) {
                queue.offer(totalRead);
            }
        }
    }
}
