/*
 * Copyright 2026 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.benchmarks.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.socket.SocketChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

public final class KQueueEchoServer {

    private KQueueEchoServer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: KQueueEchoServer <port> <maxConnections> <portFilePath>");
            System.exit(1);
        }

        final int port = Integer.parseInt(args[0]);
        final int maxConnections = Integer.parseInt(args[1]);
        final Path portFile = Path.of(args[2]);

        EventLoopGroup group = new MultiThreadIoEventLoopGroup(KQueueIoHandler.newFactory());
        final EchoServerHandler handler = new EchoServerHandler();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(group)
             .channel(KQueueServerSocketChannel.class)
             .option(ChannelOption.SO_BACKLOG, maxConnections)
             .option(ChannelOption.SO_REUSEADDR, true)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) {
                     ch.pipeline().addLast(handler);
                 }
             });

            Channel ch = b.bind(port).sync().getNow();
            int actualPort = ((InetSocketAddress) ch.localAddress()).getPort();
            writePortFile(portFile, actualPort);
            System.err.println("KQueueEchoServer started on port " + actualPort);

            ch.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    private static void writePortFile(Path portFile, int port) throws IOException {
        Files.writeString(portFile, String.valueOf(port));
    }
}
