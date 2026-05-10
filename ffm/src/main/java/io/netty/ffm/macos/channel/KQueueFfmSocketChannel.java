package io.netty.ffm.macos.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelShutdownType;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.ffm.ErrnoState;
import io.netty.ffm.macos.NativeSocket;
import io.netty.ffm.macos.generated.BsdSocket;
import io.netty.ffm.posix.FileIO;
import io.netty.ffm.posix.IovArray;
import io.netty.util.concurrent.Promise;

import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * FFM-backed TCP socket channel using kqueue for I/O multiplexing. Handles data
 * read/write (single and vectored), connection management, and half-closure.
 *
 * <p>The write path dispatches to either single-buffer write ({@link #doWriteBytes})
 * or vectored write ({@link #doWriteMultiple}) based on the number of pending messages
 * in the outbound buffer. Vectored writes use the pre-allocated {@link IovArray} from
 * {@link FfmNativeArrays} to batch multiple buffers into a single {@code writev} syscall.
 *
 * <p>The read path loops reading into allocated direct ByteBufs until EAGAIN or the
 * recv allocator signals to stop, firing pipeline events for each buffer read.
 */
public final class KQueueFfmSocketChannel extends AbstractKQueueFfmChannel implements SocketChannel {

    private static final String EXPECTED_TYPES =
            " (expected: " + ByteBuf.class.getSimpleName() + ')';
    private static final long DEFAULT_MAX_BYTES_PER_GATHERING_WRITE = Long.MAX_VALUE;

    private final Runnable flushTask = this::writeFlushedNow;
    private final ChannelConfig config;
    private long maxBytesPerGatheringWrite = DEFAULT_MAX_BYTES_PER_GATHERING_WRITE;
    private boolean allowHalfClosure;

    /**
     * Creates a new client socket channel for the given address family.
     *
     * @param eventLoop the event loop to register with
     * @param family the address family (AF_INET or AF_INET6)
     */
    public KQueueFfmSocketChannel(final EventLoop eventLoop, final int family) {
        this(eventLoop, null, createSocket(family), false);
    }

    /**
     * Creates a new client socket channel with the default IPv4 family.
     *
     * @param eventLoop the event loop to register with
     */
    public KQueueFfmSocketChannel(final EventLoop eventLoop) {
        this(eventLoop, BsdSocket.AF_INET());
    }

    /**
     * Creates a channel wrapping an accepted socket with a known remote address.
     * Used by {@link KQueueFfmServerSocketChannel} when accepting new connections.
     *
     * @param eventLoop the child event loop
     * @param parent the parent server channel
     * @param socket the accepted socket (already non-blocking)
     * @param remoteAddress the remote peer address
     */
    KQueueFfmSocketChannel(final EventLoop eventLoop, final Channel parent,
                           final NativeSocket socket, final SocketAddress remoteAddress) {
        super(eventLoop, parent, socket, remoteAddress, true);
        this.config = new DefaultChannelConfig(this);
    }

    private KQueueFfmSocketChannel(final EventLoop eventLoop, final Channel parent,
                                   final NativeSocket socket, final boolean active) {
        super(eventLoop, parent, socket, active, true);
        this.config = new DefaultChannelConfig(this);
    }

    private static NativeSocket createSocket(final int family) {
        final NativeSocket socket = NativeSocket.newStreamSocket(family);
        socket.setNonBlocking();
        return socket;
    }

    /**
     * Returns the parent server channel, or null for client-initiated channels.
     *
     * @return the parent channel
     */
    @Override
    public ServerSocketChannel parent() {
        return (ServerSocketChannel) super.parent();
    }

    /**
     * Returns the channel configuration.
     *
     * @return the config
     */
    @Override
    public ChannelConfig config() {
        return config;
    }

    /**
     * Converts heap ByteBufs to direct before queuing. The FFM write path requires
     * a contiguous memory address which only direct buffers provide.
     *
     * @param msg the outbound message
     * @return the message, potentially converted to a direct ByteBuf
     * @throws UnsupportedOperationException if the message type is not supported
     */
    @Override
    protected Object filterOutboundMessage(final Object msg) {
        if (msg instanceof ByteBuf buf) {
            if (buf.isDirect() && buf.hasMemoryAddress()) {
                return buf;
            }
            return newDirectBuffer(buf);
        }
        throw new UnsupportedOperationException(
                "unsupported message type: " + msg.getClass().getSimpleName() + EXPECTED_TYPES);
    }

    /**
     * Performs the write loop, dispatching to single or vectored write based on the
     * number of pending flushed messages. Spins up to {@code writeSpinCount} times
     * before either disabling the write filter (if all data written) or enabling it
     * (if the socket buffer is full).
     *
     * @param in the outbound buffer containing flushed messages
     * @throws Exception if a write syscall fails fatally
     */
    @Override
    protected void doWrite(final ChannelOutboundBuffer in) throws Exception {
        int writeSpinCount = config().getWriteSpinCount();
        do {
            final int msgCount = in.size();
            if (msgCount > 1 && in.current() instanceof ByteBuf) {
                writeSpinCount -= doWriteMultiple(in);
            } else if (msgCount == 0) {
                writeFilter(false);
                return;
            } else {
                writeSpinCount -= doWriteSingle(in);
            }
        } while (writeSpinCount > 0);

        if (writeSpinCount == 0) {
            writeFilter(false);
            executor().execute(flushTask);
        } else {
            writeFilter(true);
        }
    }

    /**
     * Writes a single message from the outbound buffer. Currently supports
     * {@link ByteBuf} only.
     *
     * @param in the outbound buffer
     * @return the write spin decrement (0, 1, or WRITE_STATUS_SNDBUF_FULL)
     * @throws Exception on fatal write failure
     */
    private int doWriteSingle(final ChannelOutboundBuffer in) throws Exception {
        final Object msg = in.current();
        if (msg instanceof ByteBuf buf) {
            if (buf.readableBytes() == 0) {
                in.remove();
                return 0;
            }
            return doWriteBytes(in, buf);
        }
        throw new UnsupportedOperationException(
                "unexpected message type: " + msg.getClass().getSimpleName());
    }

    /**
     * Performs a vectored write of multiple flushed ByteBufs using a single
     * {@code writev} syscall. Populates the pre-allocated {@link IovArray} from
     * the outbound buffer via {@link FfmIovArrayAdapter}, then issues the writev.
     *
     * @param in the outbound buffer containing multiple flushed ByteBufs
     * @return the write spin decrement (0, 1, or WRITE_STATUS_SNDBUF_FULL)
     * @throws Exception on fatal write failure
     */
    private int doWriteMultiple(final ChannelOutboundBuffer in) throws Exception {
        final FfmNativeArrays arrays = nativeArrays();
        final IovArray iov = arrays.cleanIovArray();
        final long maxBytes = maxBytesPerGatheringWrite;

        in.forEachFlushedMessage(arrays.iovAdapter().prepare(iov, maxBytes));

        if (iov.activeCount() == 0) {
            in.removeBytes(0);
            return 0;
        }

        final long packed = FileIO.writev(socket.fd(), iov.activeMemory(),
                iov.activeCount(), capturedState());

        if (FileIO.wouldBlock(packed)) {
            return WRITE_STATUS_SNDBUF_FULL;
        }

        final int bytesWritten = ErrnoState.unpackResult(packed);
        if (bytesWritten < 0) {
            throw new io.netty.channel.ChannelException(
                    "writev() failed: errno=" + ErrnoState.unpackErrno(packed));
        }
        if (bytesWritten > 0) {
            in.removeBytes(bytesWritten);
            return 1;
        }
        return WRITE_STATUS_SNDBUF_FULL;
    }

    /**
     * Handles the read-ready event by looping: allocate a direct ByteBuf, read into it
     * via {@link #doReadBytes}, and fire it through the pipeline. Continues until the
     * allocator handle says to stop, EAGAIN is hit, or EOF is detected.
     *
     * @param allocHandle the recv buffer allocator handle for this read batch
     */
    @Override
    void readReady(final RecvByteBufAllocator.Handle allocHandle) {
        final ChannelConfig config = config();
        if (shouldBreakReadReady()) {
            clearReadFilter0();
            return;
        }
        final ChannelPipeline pipeline = pipeline();
        final ByteBufAllocator allocator = config.getAllocator();
        allocHandle.reset(config);

        ByteBuf byteBuf = null;
        boolean close = false;
        try {
            do {
                byteBuf = allocHandle.allocate(allocator);
                allocHandle.lastBytesRead(doReadBytes(byteBuf));
                if (allocHandle.lastBytesRead() <= 0) {
                    byteBuf.release();
                    byteBuf = null;
                    close = allocHandle.lastBytesRead() < 0;
                    if (close) {
                        readPending = false;
                    }
                    break;
                }
                allocHandle.incMessagesRead(1);
                readPending = false;
                pipeline.fireChannelRead(byteBuf);
                byteBuf = null;

                if (shouldBreakReadReady()) {
                    break;
                }
            } while (allocHandle.continueReading());

            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();

            if (close) {
                shutdownInput(false);
            }
        } catch (final Throwable t) {
            handleReadException(pipeline, byteBuf, t, close, allocHandle);
        } finally {
            if (shouldStopReading(config)) {
                clearReadFilter0();
            }
        }
    }

    /**
     * Shuts down the specified direction (inbound or outbound) of this socket.
     *
     * @param type the shutdown direction
     * @param promise completed when shutdown finishes
     */
    @Override
    protected void doShutdown(final ChannelShutdownType type, final Promise<Void> promise) {
        if (type.data() != null) {
            promise.setFailure(new IllegalArgumentException(
                    "ChannelShutdownType with data is not supported: " + type));
            return;
        }
        try {
            switch (type.direction()) {
                case Outbound -> socket.shutdown(NativeSocket.ShutdownMode.WRITE);
                case Inbound -> socket.shutdown(NativeSocket.ShutdownMode.READ);
            }
        } catch (final Throwable cause) {
            promise.setFailure(cause);
            return;
        }
        promise.setSuccess(null);
    }

    @Override
    protected boolean isAllowHalfClosure() {
        return allowHalfClosure;
    }

    @Override
    protected SocketAddress localAddress0() {
        return super.localAddress0();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return super.remoteAddress0();
    }

    private void handleReadException(final ChannelPipeline pipeline, final ByteBuf byteBuf,
                                     final Throwable cause, final boolean close,
                                     final RecvByteBufAllocator.Handle allocHandle) {
        if (byteBuf != null) {
            if (byteBuf.isReadable()) {
                pipeline.fireChannelRead(byteBuf);
            } else {
                byteBuf.release();
            }
        }
        allocHandle.readComplete();
        pipeline.fireChannelReadComplete();
        pipeline.fireExceptionCaught(cause);
        if (close || cause instanceof java.io.IOException) {
            shutdownInput(false);
        }
    }
}
