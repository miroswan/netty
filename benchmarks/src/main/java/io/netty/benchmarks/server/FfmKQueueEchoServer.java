package io.netty.benchmarks.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.ffm.macos.KQueueFfmIoHandler;
import io.netty.ffm.macos.channel.KQueueFfmServerSocketChannel;
import io.netty.ffm.macos.channel.KQueueFfmSocketChannel;
import io.netty.ffm.macos.generated.BsdSocket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Echo server backed by the FFM kqueue transport. Functionally identical to
 * {@link KQueueEchoServer} but uses zero-JNI FFM downcall handles for all
 * I/O operations.
 */
public final class FfmKQueueEchoServer {

    private FfmKQueueEchoServer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: FfmKQueueEchoServer <port> <maxConnections> <portFilePath>");
            System.exit(1);
        }

        final int port = Integer.parseInt(args[0]);
        final int maxConnections = Integer.parseInt(args[1]);
        final Path portFile = Path.of(args[2]);

        EventLoopGroup group = new MultiThreadIoEventLoopGroup(KQueueFfmIoHandler.newFactory());
        final EchoServerHandler handler = new EchoServerHandler();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(group)
             .channelFactory(KQueueFfmServerSocketChannel::new)
             .option(ChannelOption.SO_BACKLOG, maxConnections)
             .option(ChannelOption.SO_REUSEADDR, true)
             .childHandler(new ChannelInitializer<>() {
                 @Override
                 public void initChannel(Channel ch) {
                     ch.pipeline().addLast(handler);
                 }
             });

            Channel ch = b.bind(port).sync().getNow();
            int actualPort = ((InetSocketAddress) ch.localAddress()).getPort();
            writePortFile(portFile, actualPort);
            System.err.println("FfmKQueueEchoServer started on port " + actualPort);

            ch.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    private static void writePortFile(Path portFile, int port) throws IOException {
        Files.writeString(portFile, String.valueOf(port));
    }
}
