package io.netty.ffm.macos.channel;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelShutdownType;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.ffm.ErrnoState;
import io.netty.ffm.macos.NativeSocket;
import io.netty.ffm.macos.SocketIO;
import io.netty.ffm.macos.generated.BsdSocket;
import io.netty.util.concurrent.Promise;

import java.lang.foreign.Arena;
import java.net.SocketAddress;

/**
 * FFM-backed TCP server socket channel using kqueue for accept notification.
 * Binds to a local address, listens for connections, and creates child
 * {@link KQueueFfmSocketChannel} instances for each accepted connection.
 *
 * <p>The accept loop runs when {@code EVFILT_READ} fires on the server socket,
 * accepting connections in a tight loop until EAGAIN or the recv allocator
 * signals to stop.
 */
public final class KQueueFfmServerSocketChannel extends AbstractKQueueFfmChannel
        implements ServerSocketChannel {

    private static final int DEFAULT_BACKLOG = 128;

    private final KQueueFfmServerSocketChannelConfig config;
    private final EventLoopGroup childEventLoopGroup;

    /**
     * Creates a new server socket channel for the default IPv4 family.
     *
     * @param eventLoop the event loop for accepting connections
     * @param childEventLoopGroup the event loop group for child channels
     */
    public KQueueFfmServerSocketChannel(final EventLoop eventLoop,
                                        final EventLoopGroup childEventLoopGroup) {
        this(eventLoop, childEventLoopGroup, BsdSocket.AF_INET());
    }

    /**
     * Creates a new server socket channel for the specified address family.
     *
     * @param eventLoop the event loop for accepting connections
     * @param childEventLoopGroup the event loop group for child channels
     * @param family the address family (AF_INET, AF_INET6, or AF_UNIX)
     */
    public KQueueFfmServerSocketChannel(final EventLoop eventLoop,
                                        final EventLoopGroup childEventLoopGroup,
                                        final int family) {
        super(eventLoop, null, createSocket(family), false, false);
        this.childEventLoopGroup = childEventLoopGroup;
        this.config = new KQueueFfmServerSocketChannelConfig(this, socket);
    }

    private static NativeSocket createSocket(final int family) {
        final NativeSocket socket = NativeSocket.newStreamSocket(family);
        socket.setNonBlocking();
        return socket;
    }

    /**
     * Returns the channel configuration.
     *
     * @return the config
     */
    @Override
    public KQueueFfmServerSocketChannelConfig config() {
        return config;
    }

    /**
     * Returns the event loop group used for child channels (accepted connections).
     *
     * @return the child event loop group
     */
    @Override
    public EventLoopGroup childEventExecutorGroup() {
        return childEventLoopGroup;
    }

    /**
     * Binds the server socket and begins listening for connections.
     *
     * @param localAddr the local address to bind to
     * @param promise completed when bind and listen succeed
     */
    @Override
    protected void doBind(final SocketAddress localAddr, final Promise<Void> promise) {
        super.doBind(localAddr, promise);
        if (promise.isSuccess()) {
            socket.listen(config.getBacklog());
            active = true;
        }
    }

    /**
     * Server channels do not write data. This method throws unconditionally.
     *
     * @param in the outbound buffer (unused)
     * @throws UnsupportedOperationException always
     */
    @Override
    protected void doWrite(final ChannelOutboundBuffer in) throws Exception {
        throw new UnsupportedOperationException("Server channel does not write data");
    }

    /**
     * Server channels do not support shutdown directions.
     *
     * @param type the shutdown type (unused)
     * @param promise failed with UnsupportedOperationException
     */
    @Override
    protected void doShutdown(final ChannelShutdownType type, final Promise<Void> promise) {
        promise.setFailure(new UnsupportedOperationException("Server channel does not support shutdown"));
    }

    /**
     * Accepts connections in a loop when the server socket becomes readable.
     * For each accepted fd, creates a new {@link KQueueFfmSocketChannel} and fires
     * it through the pipeline as a channel read event.
     *
     * @param allocHandle the recv allocator handle controlling the accept loop
     */
    @Override
    void readReady(final RecvByteBufAllocator.Handle allocHandle) {
        final ChannelConfig cfg = config();
        final ChannelPipeline pipeline = pipeline();
        allocHandle.reset(cfg);

        try {
            do {
                final long result = socket.accept(capturedState());

                if (SocketIO.acceptWouldBlock(result)) {
                    allocHandle.lastBytesRead(-1);
                    break;
                }
                if (!SocketIO.acceptIsSuccess(result)) {
                    allocHandle.lastBytesRead(-1);
                    break;
                }

                final int acceptedFd = ErrnoState.unpackResult(result);
                final NativeSocket acceptedSocket = NativeSocket.fromFd(acceptedFd, socket.family());
                acceptedSocket.setNonBlocking();

                SocketAddress remoteAddr = null;
                try (final Arena tempArena = Arena.ofConfined()) {
                    remoteAddr = acceptedSocket.remoteAddress(tempArena);
                }

                allocHandle.lastBytesRead(1);
                allocHandle.incMessagesRead(1);
                readPending = false;

                final EventLoop childLoop = childEventLoopGroup.next();
                final KQueueFfmSocketChannel childChannel = new KQueueFfmSocketChannel(
                        childLoop, this, acceptedSocket, remoteAddr);
                pipeline.fireChannelRead(childChannel);

            } while (allocHandle.continueReading());

            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
        } catch (final Throwable t) {
            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
            pipeline.fireExceptionCaught(t);
        } finally {
            if (shouldStopReading(cfg)) {
                clearReadFilter0();
            }
        }
    }
}
