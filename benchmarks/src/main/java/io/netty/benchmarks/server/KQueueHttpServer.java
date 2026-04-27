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
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.util.concurrent.Future;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderValues.CLOSE;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpHeaderValues.TEXT_PLAIN;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

public final class KQueueHttpServer {

    private KQueueHttpServer() {
    }

    private static final byte[] CONTENT = "Hello World".getBytes();
    private static final HelloWorldHandler HANDLER = new HelloWorldHandler();

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: KQueueHttpServer <port> <maxConnections> <portFilePath>");
            System.exit(1);
        }

        final int port = Integer.parseInt(args[0]);
        final int maxConnections = Integer.parseInt(args[1]);
        final Path portFile = Path.of(args[2]);

        EventLoopGroup group = new MultiThreadIoEventLoopGroup(KQueueIoHandler.newFactory());
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(group)
             .channel(KQueueServerSocketChannel.class)
             .option(ChannelOption.SO_BACKLOG, maxConnections)
             .option(ChannelOption.SO_REUSEADDR, true)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) {
                     ch.pipeline()
                       .addLast(new HttpServerCodec())
                       .addLast(HANDLER);
                 }
             });

            Channel ch = b.bind(port).sync().getNow();
            int actualPort = ((InetSocketAddress) ch.localAddress()).getPort();
            writePortFile(portFile, actualPort);
            System.err.println("KQueueHttpServer started on port " + actualPort);

            ch.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    private static void writePortFile(Path portFile, int port) throws IOException {
        Files.writeString(portFile, String.valueOf(port));
    }

    private static final class HelloWorldHandler extends SimpleChannelInboundHandler<HttpObject> {

        @Override
        public boolean isSharable() {
            return true;
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            if (msg instanceof HttpRequest req) {
                boolean keepAlive = HttpUtil.isKeepAlive(req);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        req.protocolVersion(), OK, Unpooled.wrappedBuffer(CONTENT));
                response.headers()
                        .set(CONTENT_TYPE, TEXT_PLAIN)
                        .setInt(CONTENT_LENGTH, response.content().readableBytes());

                if (keepAlive) {
                    if (!req.protocolVersion().isKeepAliveDefault()) {
                        response.headers().set(CONNECTION, KEEP_ALIVE);
                    }
                } else {
                    response.headers().set(CONNECTION, CLOSE);
                }

                Future<Void> f = ctx.write(response);
                if (!keepAlive) {
                    f.addListener(future -> ctx.close());
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}
