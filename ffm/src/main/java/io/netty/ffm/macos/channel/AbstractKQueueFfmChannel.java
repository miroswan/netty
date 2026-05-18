package io.netty.ffm.macos.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelShutdownDirection;
import io.netty.channel.ChannelShutdownType;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.EventLoop;
import io.netty.channel.IoEvent;
import io.netty.channel.IoRegistration;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.kqueue.KQueueIoEvent;
import io.netty.channel.kqueue.KQueueIoHandle;
import io.netty.channel.kqueue.KQueueIoOps;
import io.netty.ffm.ErrnoState;
import io.netty.ffm.macos.KqueueIO;
import io.netty.ffm.macos.NativeSocket;
import io.netty.ffm.macos.SocketIO;
import io.netty.ffm.posix.FileIO;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.CompletionHandler;
import io.netty.util.concurrent.Promise;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.UnresolvedAddressException;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * Base class for FFM-backed kqueue channels. Manages kqueue filter registration,
 * event dispatch, connection lifecycle, and read/write filter state. Subclasses
 * implement {@link #readReady} for their specific read behavior (stream read vs accept).
 *
 * <p>Uses {@link NativeSocket} for socket operations and {@link KqueueIO} constants
 * for filter management. All I/O goes through FFM downcall handles — no JNI.
 *
 * <p>The inner {@link KQueueIoHandle} implementation dispatches kqueue events to the
 * appropriate handler methods: {@code EVFILT_WRITE} for connection completion and
 * write readiness, {@code EVFILT_READ} for data availability, and
 * {@code EVFILT_SOCK + NOTE_RDHUP} or {@code EV_EOF} for peer shutdown detection.
 */
abstract class AbstractKQueueFfmChannel extends AbstractChannel {

    static final KQueueIoOps READ_ENABLED_OPS = KQueueIoOps.newOps(
            KqueueIO.EVFILT_READ, (short) (KqueueIO.EV_ADD | KqueueIO.EV_ENABLE), 0);
    static final KQueueIoOps WRITE_ENABLED_OPS = KQueueIoOps.newOps(
            KqueueIO.EVFILT_WRITE, (short) (KqueueIO.EV_ADD | KqueueIO.EV_ENABLE), 0);
    static final KQueueIoOps READ_DISABLED_OPS = KQueueIoOps.newOps(
            KqueueIO.EVFILT_READ, (short) (KqueueIO.EV_DELETE | KqueueIO.EV_DISABLE), 0);
    static final KQueueIoOps WRITE_DISABLED_OPS = KQueueIoOps.newOps(
            KqueueIO.EVFILT_WRITE, (short) (KqueueIO.EV_DELETE | KqueueIO.EV_DISABLE), 0);

    private final KQueueIoHandle ioHandle = new KQueueFfmIoHandleImpl();
    final NativeSocket socket;

    private IoRegistration registration;
    private Promise<Void> connectPromise;
    private SocketAddress requestedRemoteAddress;
    private boolean readFilterEnabled;
    private boolean writeFilterEnabled;
    boolean readPending;
    boolean readReadyRunnablePending;
    boolean inputClosedSeenErrorOnRead;
    protected volatile boolean active;
    volatile SocketAddress local;
    volatile SocketAddress remote;

    /**
     * Creates a channel wrapping an existing socket. Used for newly created sockets
     * (e.g. from {@code socket(2)}) where the active state is known.
     *
     * @param eventLoop the event loop this channel is registered with
     * @param parent the parent channel, or null
     * @param socket the FFM native socket to wrap
     * @param active whether the socket is already active (connected or bound)
     * @param hasDisconnect whether the channel supports disconnect as distinct from close
     */
    AbstractKQueueFfmChannel(final EventLoop eventLoop, final Channel parent,
                             final NativeSocket socket, final boolean active,
                             final boolean hasDisconnect) {
        super(eventLoop, KQueueIoHandle.class, parent, DefaultChannelId.newInstance(), hasDisconnect);
        this.socket = socket;
        this.active = active;
    }

    /**
     * Creates a channel wrapping an accepted socket with a known remote address.
     * The channel is immediately marked as active.
     *
     * @param eventLoop the event loop this channel is registered with
     * @param parent the parent server channel
     * @param socket the accepted FFM native socket
     * @param remote the remote address of the accepted connection
     * @param hasDisconnect whether the channel supports disconnect as distinct from close
     */
    AbstractKQueueFfmChannel(final EventLoop eventLoop, final Channel parent,
                             final NativeSocket socket, final SocketAddress remote,
                             final boolean hasDisconnect) {
        super(eventLoop, KQueueIoHandle.class, parent, DefaultChannelId.newInstance(), hasDisconnect);
        this.socket = socket;
        this.active = true;
        this.remote = remote;
    }

    /**
     * Returns the kqueue registration for this channel. Must only be called after
     * successful registration.
     *
     * @return the active IoRegistration
     */
    protected final IoRegistration registration() {
        assert registration != null;
        return registration;
    }

    /**
     * Returns the pre-allocated captured state segment from the event loop's
     * {@link FfmNativeArrays}. Used for errno capture on every FFM syscall.
     *
     * @return the shared capturedState segment
     */
    protected final MemorySegment capturedState() {
        return ((FfmNativeArrays) registration.attachment()).capturedState();
    }

    /**
     * Returns the event loop's shared native arrays (IovArray, capturedState, etc.).
     *
     * @return the per-event-loop FfmNativeArrays
     */
    protected final FfmNativeArrays nativeArrays() {
        return (FfmNativeArrays) registration.attachment();
    }

    /**
     * Returns whether this channel is active (connected for client sockets,
     * bound for server sockets).
     *
     * @return {@code true} if the channel is active
     */
    @Override
    public boolean isActive() {
        return active;
    }

    /**
     * Returns whether the underlying socket file descriptor is still open.
     *
     * @return {@code true} if the socket has not been closed
     */
    @Override
    public boolean isOpen() {
        return socket.isOpen();
    }

    /**
     * Returns the locally bound address, or null if not yet bound.
     *
     * @return the local address
     */
    @Override
    protected SocketAddress localAddress0() {
        return local;
    }

    /**
     * Returns the remote address this channel is connected to, or null if not connected.
     *
     * @return the remote address
     */
    @Override
    protected SocketAddress remoteAddress0() {
        return remote;
    }

    /**
     * Registers this channel's {@link KQueueIoHandle} with the event loop. On success,
     * subscribes to {@code EVFILT_SOCK} with {@code NOTE_RDHUP} for peer shutdown detection,
     * and re-enables any previously active read/write filters.
     *
     * @param promise completed when registration succeeds or fails
     */
    @Override
    protected void doRegister(final Promise<Void> promise) {
        executor().register(ioHandle).addListener(f -> {
            if (f.isSuccess()) {
                this.registration = (IoRegistration) f.getNow();
                readReadyRunnablePending = false;

                submit(KQueueIoOps.newOps(KqueueIO.EVFILT_SOCK,
                        (short) (KqueueIO.EV_ADD), KqueueIO.NOTE_RDHUP));

                if (writeFilterEnabled) {
                    submit(WRITE_ENABLED_OPS);
                }
                if (readFilterEnabled) {
                    submit(READ_ENABLED_OPS);
                }
                promise.setSuccess(null);
            } else {
                promise.setFailure(f.cause());
            }
        });
    }

    /**
     * Deregisters this channel from kqueue by disabling all filters and cancelling
     * the registration. Safe to call multiple times.
     *
     * @param promise completed when deregistration is done
     */
    @Override
    protected void doDeregister(final Promise<Void> promise) {
        final IoRegistration reg = this.registration;
        if (reg != null) {
            readFilter(false);
            writeFilter(false);
            clearRdHup0();
            reg.cancel();
            this.registration = null;
        }
        promise.setSuccess(null);
    }

    /**
     * Closes the channel by deregistering from kqueue, marking inactive, and closing
     * the underlying socket file descriptor.
     *
     * @param promise completed when the close operation finishes
     */
    @Override
    protected void doClose(final Promise<Void> promise) {
        doDeregister(newPromise());
        active = false;
        inputClosedSeenErrorOnRead = true;
        try {
            socket.close();
        } catch (final Throwable cause) {
            promise.setFailure(cause);
            return;
        }
        promise.setSuccess(null);
    }

    /**
     * Disconnects by closing the channel. For connection-oriented sockets,
     * disconnect and close are equivalent.
     *
     * @param promise completed when disconnect finishes
     */
    @Override
    protected void doDisconnect(final Promise<Void> promise) {
        doClose(promise);
    }

    /**
     * Arms the read interest by enabling {@code EVFILT_READ} on the kqueue. The next
     * time the socket has data available, {@link #readReady} will be called.
     *
     * @throws Exception if filter submission fails
     */
    @Override
    protected final void doBeginRead() throws Exception {
        readPending = true;
        readFilter(true);
    }

    /**
     * Binds the socket to the given local address and caches the result.
     *
     * @param localAddr the address to bind to
     * @param promise completed when bind succeeds or fails
     */
    @Override
    protected void doBind(final SocketAddress localAddr, final Promise<Void> promise) {
        try {
            if (localAddr instanceof InetSocketAddress) {
                checkResolvable((InetSocketAddress) localAddr);
            }
            try (final Arena tempArena = Arena.ofConfined()) {
                socket.bind(tempArena, (InetSocketAddress) localAddr);
                this.local = socket.localAddress(tempArena);
            }
        } catch (final Throwable cause) {
            promise.setFailure(cause);
            return;
        }
        promise.setSuccess(null);
    }

    /**
     * Initiates a non-blocking connect. If the connection completes immediately,
     * the promise is fulfilled. Otherwise, the write filter is enabled and
     * {@link #finishConnect()} will be called when the socket becomes writable.
     *
     * @param remoteAddress the remote address to connect to
     * @param localAddress optional local address to bind before connecting
     * @param promise completed when connect succeeds or fails
     */
    @Override
    protected void doConnect(final SocketAddress remoteAddress, final SocketAddress localAddress,
                             final Promise<Void> promise) {
        final boolean connected;
        requestedRemoteAddress = remoteAddress;
        try {
            connected = doConnect0(remoteAddress, localAddress);
        } catch (final Throwable cause) {
            promise.setFailure(cause);
            return;
        }
        if (connected) {
            active = true;
            promise.setSuccess(null);
        } else {
            connectPromise = promise;
        }
    }

    /**
     * Performs the actual non-blocking connect syscall. Validates addresses, optionally
     * binds to a local address, then calls {@link NativeSocket#connect}. If the connect
     * returns EINPROGRESS, enables the write filter for completion notification.
     *
     * @param remoteAddress the remote address to connect to
     * @param localAddress optional local address to bind before connecting
     * @return {@code true} if connected immediately, {@code false} if in progress
     * @throws Exception if address resolution or connect fails fatally
     */
    private boolean doConnect0(final SocketAddress remoteAddress,
                               final SocketAddress localAddress) throws Exception {
        if (localAddress instanceof InetSocketAddress) {
            checkResolvable((InetSocketAddress) localAddress);
        }
        if (remoteAddress instanceof InetSocketAddress) {
            checkResolvable((InetSocketAddress) remoteAddress);
        }
        if (remote != null) {
            throw new AlreadyConnectedException();
        }

        try (final Arena tempArena = Arena.ofConfined()) {
            if (localAddress != null) {
                socket.bind(tempArena, (InetSocketAddress) localAddress);
            }

            final long result = socket.connect(
                    tempArena, (InetSocketAddress) remoteAddress, capturedState());
            final boolean connected = SocketIO.connectIsConnected(result);
            if (!connected && !SocketIO.connectIsInProgress(result)) {
                throw new ConnectException("connect failed: errno=" + ErrnoState.unpackErrno(result));
            }
            if (!connected) {
                writeFilter(true);
            }
            local = socket.localAddress(tempArena);
            return connected;
        }
    }

    /**
     * Returns {@code true} when the write filter is enabled, indicating that a
     * write-flush is already scheduled (either for connect completion or pending writes).
     * This prevents the framework from scheduling redundant flush attempts.
     *
     * @return {@code true} if write interest is registered with kqueue
     */
    @Override
    protected boolean isWriteFlushedScheduled() {
        return writeFilterEnabled;
    }

    /**
     * Enables or disables the {@code EVFILT_READ} kqueue filter. Only submits a
     * change if the state actually differs from the current setting.
     *
     * @param enabled {@code true} to subscribe to read events, {@code false} to unsubscribe
     */
    void readFilter(final boolean enabled) {
        if (this.readFilterEnabled != enabled) {
            this.readFilterEnabled = enabled;
            submit(enabled ? READ_ENABLED_OPS : READ_DISABLED_OPS);
        }
    }

    /**
     * Enables or disables the {@code EVFILT_WRITE} kqueue filter. Only submits a
     * change if the state actually differs from the current setting.
     *
     * @param enabled {@code true} to subscribe to write events, {@code false} to unsubscribe
     */
    void writeFilter(final boolean enabled) {
        if (this.writeFilterEnabled != enabled) {
            this.writeFilterEnabled = enabled;
            submit(enabled ? WRITE_ENABLED_OPS : WRITE_DISABLED_OPS);
        }
    }

    /**
     * Reads bytes from the socket into the given direct ByteBuf using FFM. Creates a
     * zero-copy {@link MemorySegment} view of the ByteBuf's off-heap memory and passes
     * it to {@link FileIO#read}.
     *
     * @param byteBuf a direct ByteBuf with writable space
     * @return the number of bytes read, 0 for EAGAIN, or -1 for EOF
     * @throws Exception if the read syscall fails with a non-retriable error
     */
    protected final int doReadBytes(final ByteBuf byteBuf) throws Exception {
        final int writerIndex = byteBuf.writerIndex();
        final int writableBytes = byteBuf.writableBytes();
        recvBufAllocHandle().attemptedBytesRead(writableBytes);
        assert byteBuf.hasMemoryAddress() : "FFM channel requires direct ByteBuf with memory address";

        final MemorySegment seg = MemorySegment.ofAddress(byteBuf.memoryAddress() + writerIndex)
                .reinterpret(writableBytes);
        final long packed = FileIO.read(socket.fd(), seg, writableBytes, capturedState());

        if (FileIO.wouldBlock(packed)) {
            return 0;
        }
        if (FileIO.isEof(packed)) {
            return -1;
        }
        final int bytesRead = ErrnoState.unpackResult(packed);
        if (bytesRead < 0) {
            throw new ChannelException("read() failed: errno=" + ErrnoState.unpackErrno(packed));
        }
        byteBuf.writerIndex(writerIndex + bytesRead);
        return bytesRead;
    }

    /**
     * Writes bytes from the given direct ByteBuf to the socket using FFM. Creates a
     * zero-copy {@link MemorySegment} view of the ByteBuf's readable region and passes
     * it to {@link FileIO#write}.
     *
     * @param in the channel outbound buffer (for removeBytes accounting)
     * @param buf the direct ByteBuf containing data to write
     * @return 1 if bytes were written, or {@code WRITE_STATUS_SNDBUF_FULL} if the socket
     *         buffer is full (EAGAIN)
     * @throws Exception if the write syscall fails with a non-retriable error
     */
    protected final int doWriteBytes(final ChannelOutboundBuffer in, final ByteBuf buf) throws Exception {
        final int readableBytes = buf.readableBytes();
        assert buf.hasMemoryAddress() : "FFM channel requires direct ByteBuf with memory address";

        final MemorySegment seg = MemorySegment.ofAddress(buf.memoryAddress() + buf.readerIndex())
                .reinterpret(readableBytes);
        final long packed = FileIO.write(socket.fd(), seg, readableBytes, capturedState());

        if (FileIO.wouldBlock(packed)) {
            return WRITE_STATUS_SNDBUF_FULL;
        }
        final int bytesWritten = ErrnoState.unpackResult(packed);
        if (bytesWritten < 0) {
            throw new ChannelException("write() failed: errno=" + ErrnoState.unpackErrno(packed));
        }
        if (bytesWritten > 0) {
            in.removeBytes(bytesWritten);
            return 1;
        }
        return WRITE_STATUS_SNDBUF_FULL;
    }

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the original.
     * Used by {@code filterOutboundMessage} to ensure all writes go through direct memory.
     *
     * @param buf the potentially heap-backed buffer to convert
     * @return a direct ByteBuf containing the same data
     */
    protected final ByteBuf newDirectBuffer(final ByteBuf buf) {
        return newDirectBuffer(buf, buf);
    }

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, releasing the holder.
     * The holder is released after the copy, which may or may not be the same object as buf.
     *
     * @param holder the object to release after copying
     * @param buf the buffer to copy into direct memory
     * @return a direct ByteBuf containing the same data
     */
    protected final ByteBuf newDirectBuffer(final Object holder, final ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.release(holder);
            return Unpooled.EMPTY_BUFFER;
        }
        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            final ByteBuf directBuf = alloc.directBuffer(readableBytes);
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }
        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf == null) {
            final ByteBuf pooled = alloc.directBuffer(readableBytes);
            pooled.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return pooled;
        }
        directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
        ReferenceCountUtil.safeRelease(holder);
        return directBuf;
    }

    /**
     * Returns whether this channel supports half-closure (shutting down input while
     * keeping output open). Subclasses override to return {@code true} for TCP channels
     * that opt in to half-closure semantics.
     *
     * @return {@code false} by default
     */
    protected boolean isAllowHalfClosure() {
        return false;
    }

    /**
     * Returns whether reading should stop based on the current read-pending state
     * and auto-read configuration.
     *
     * @param config the channel config to check auto-read setting
     * @return {@code true} if the channel should not attempt further reads
     */
    final boolean shouldStopReading(final ChannelConfig config) {
        return !readPending && !config.isAutoRead();
    }

    /**
     * Clears the read filter safely. If called from the event loop thread, clears
     * immediately. Otherwise, schedules the clear on the event loop to avoid
     * concurrent filter modification.
     */
    final void clearReadFilter() {
        if (isRegistered()) {
            final EventLoop loop = executor();
            if (loop.inEventLoop()) {
                clearReadFilter0();
            } else {
                loop.execute(() -> {
                    if (!readPending && !config().isAutoRead()) {
                        clearReadFilter0();
                    }
                });
            }
        } else {
            readFilterEnabled = false;
        }
    }

    /**
     * Immediately clears the read filter and marks no read as pending. Must be
     * called from the event loop thread.
     */
    final void clearReadFilter0() {
        assert executor().inEventLoop();
        readPending = false;
        readFilter(false);
    }

    /**
     * Called when data is available for reading. Subclasses implement this to perform
     * their specific read behavior — stream channels read bytes, server channels accept
     * connections.
     *
     * @param allocHandle the recv buffer allocator handle for this read batch
     */
    abstract void readReady(RecvByteBufAllocator.Handle allocHandle);

    /**
     * Creates a kqueue-aware recv buffer allocator handle that tracks EOF state.
     * Edge-triggered kqueue requires reading until EAGAIN even after EOF is signaled.
     *
     * @return a new {@link KQueueFfmRecvAllocHandle}
     */
    @Override
    protected RecvByteBufAllocator.Handle newRecvBufAllocHandle() {
        return new KQueueFfmRecvAllocHandle(
                (RecvByteBufAllocator.ExtendedHandle) super.newRecvBufAllocHandle());
    }

    /**
     * Submits a kqueue filter operation via the registration. No-ops if the channel
     * is already closed to avoid affecting a reused file descriptor.
     *
     * @param ops the kqueue operations to submit
     */
    private void submit(final KQueueIoOps ops) {
        if (!isOpen()) {
            return;
        }
        try {
            registration.submit(ops);
        } catch (final Exception e) {
            throw new ChannelException(e);
        }
    }

    /**
     * Removes the {@code EVFILT_SOCK + NOTE_RDHUP} subscription to stop receiving
     * peer shutdown notifications. Called during deregistration and after processing EOF.
     */
    private void clearRdHup0() {
        submit(KQueueIoOps.newOps(KqueueIO.EVFILT_SOCK,
                (short) (KqueueIO.EV_DELETE | KqueueIO.EV_DISABLE), KqueueIO.NOTE_RDHUP));
    }

    /**
     * Called when the socket becomes writable ({@code EVFILT_WRITE} fires). If a
     * connect is pending, completes it. Otherwise, flushes pending writes.
     */
    private void writeReady() {
        if (connectPromise != null) {
            finishConnect();
        } else if (!socket.isOutputShutdown()) {
            writeFlushedNow();
        }
    }

    /**
     * Completes a pending non-blocking connect by checking whether the socket is now
     * connected. On success, marks the channel active and fulfills the connect promise.
     */
    private void finishConnect() {
        assert executor().inEventLoop();
        assert connectPromise != null;
        final Promise<Void> promise = connectPromise;
        final boolean connected;
        try {
            connected = doFinishConnect();
        } catch (final Throwable cause) {
            connectPromise = null;
            promise.setFailure(cause);
            return;
        }
        if (connected) {
            active = true;
            connectPromise = null;
            promise.setSuccess(null);
        }
    }

    /**
     * Checks whether the non-blocking connect has completed by querying {@code SO_ERROR}.
     * If connected, disables the write filter and caches the remote address. If still
     * in progress, keeps the write filter enabled for the next notification.
     *
     * @return {@code true} if the connection is established
     * @throws Exception if {@code SO_ERROR} indicates a connection failure
     */
    private boolean doFinishConnect() throws Exception {
        if (socket.finishConnect(capturedState())) {
            writeFilter(false);
            if (requestedRemoteAddress instanceof InetSocketAddress) {
                try (final Arena tempArena = Arena.ofConfined()) {
                    remote = socket.remoteAddress(tempArena);
                }
            }
            requestedRemoteAddress = null;
            return true;
        }
        writeFilter(true);
        return false;
    }

    /**
     * Attempts to fail the connect promise with the given cause. Used when a connection
     * error is detected via EOF or read error before the connect promise is fulfilled.
     *
     * @param cause the failure reason
     * @return {@code true} if the promise was successfully failed
     */
    final boolean failConnectPromise(final Throwable cause) {
        if (connectPromise != null) {
            final Promise<Void> promise = connectPromise;
            connectPromise = null;
            if (promise.tryFailure((cause instanceof ConnectException) ? cause
                    : new ConnectException("failed to connect").initCause(cause))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handles input shutdown, either from explicit EOF detection or error. If
     * half-closure is allowed, shuts down only the input side. Otherwise, closes
     * the entire channel.
     *
     * @param readEOF {@code true} if this was triggered by an EOF event
     */
    void shutdownInput(final boolean readEOF) {
        if (readEOF && connectPromise != null) {
            finishConnect();
        }
        if (!socket.isInputShutdown()) {
            if (isAllowHalfClosure()) {
                ioTransport().shutdown(ChannelShutdownType.newInbound(), CompletionHandler.ignore());
                if (shouldStopReading(config())) {
                    clearReadFilter0();
                }
            } else {
                close(CompletionHandler.ignore());
                return;
            }
        }
        if (!readEOF && !inputClosedSeenErrorOnRead) {
            inputClosedSeenErrorOnRead = true;
            pipeline().fireChannelShutdown(ChannelShutdownType.newInbound());
        }
    }

    /**
     * Handles EOF detection from kqueue ({@code EV_EOF} or {@code EVFILT_SOCK + NOTE_RDHUP}).
     * Marks the allocator handle as EOF-aware, attempts to read any remaining buffered data,
     * then shuts down input. Clears the RDHUP subscription to avoid repeated wakeups.
     */
    private void readEOF() {
        final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
        if (allocHandle instanceof KQueueFfmRecvAllocHandle) {
            ((KQueueFfmRecvAllocHandle) allocHandle).readEOF();
        }
        if (isActive()) {
            readReady(allocHandle);
        } else {
            shutdownInput(true);
        }
        clearRdHup0();
    }

    /**
     * Returns whether the read loop should break early due to input being shut down
     * and either an error being seen or half-closure not being allowed.
     *
     * @return {@code true} if further reads should be suppressed
     */
    final boolean shouldBreakReadReady() {
        return isShutdown(ChannelShutdownDirection.Inbound) && (inputClosedSeenErrorOnRead || !isAllowHalfClosure());
    }

    /**
     * Validates that the given address is resolved (not a hostname that failed DNS lookup).
     *
     * @param addr the address to validate
     * @throws UnresolvedAddressException if the address is unresolved
     */
    protected static void checkResolvable(final InetSocketAddress addr) {
        if (addr.isUnresolved()) {
            throw new UnresolvedAddressException();
        }
    }

    /**
     * Kqueue event dispatcher. Receives all events for this channel's file descriptor
     * and routes them to the appropriate handler method based on the event filter and flags.
     */
    private final class KQueueFfmIoHandleImpl implements KQueueIoHandle {

        /**
         * Returns the file descriptor number for kqueue registration.
         *
         * @return the socket fd
         */
        @Override
        public int ident() {
            return socket.fd();
        }

        /**
         * Initiates channel close when the handle is closed externally.
         */
        @Override
        public void close() {
            ioTransport().close(CompletionHandler.ignore());
        }

        /**
         * Dispatches a kqueue event to the appropriate channel handler. Processing order:
         * <ol>
         *   <li>{@code EVFILT_WRITE} → connection completion or write flush</li>
         *   <li>{@code EVFILT_READ} → data available for reading</li>
         *   <li>{@code EVFILT_SOCK + NOTE_RDHUP} → peer half-close</li>
         *   <li>{@code EV_EOF} flag → connection reset or full close</li>
         * </ol>
         *
         * @param registration the channel's kqueue registration
         * @param event the kqueue event containing filter, flags, and fflags
         */
        @Override
        public void handle(final IoRegistration registration, final IoEvent event) {
            final KQueueIoEvent kqueueEvent = (KQueueIoEvent) event;
            final short filter = kqueueEvent.filter();
            final short flags = kqueueEvent.flags();
            final int fflags = kqueueEvent.fflags();

            if (filter == KqueueIO.EVFILT_WRITE) {
                writeReady();
            } else if (filter == KqueueIO.EVFILT_READ) {
                readReady(recvBufAllocHandle());
            } else if (filter == KqueueIO.EVFILT_SOCK && (fflags & KqueueIO.NOTE_RDHUP) != 0) {
                readEOF();
                return;
            }

            if ((flags & KqueueIO.EV_EOF) != 0) {
                readEOF();
            }
        }
    }

    /**
     * {@link RecvByteBufAllocator.Handle} wrapper that tracks EOF state for edge-triggered
     * kqueue. When EOF is detected, {@link #continueReading()} returns {@code true} to
     * ensure all buffered data is consumed before the channel reports shutdown.
     */
    private static final class KQueueFfmRecvAllocHandle implements RecvByteBufAllocator.Handle {
        private final RecvByteBufAllocator.ExtendedHandle delegate;
        private boolean readEOF;

        KQueueFfmRecvAllocHandle(final RecvByteBufAllocator.ExtendedHandle delegate) {
            this.delegate = delegate;
        }

        /**
         * Marks that an EOF event was received from kqueue.
         */
        void readEOF() {
            this.readEOF = true;
        }

        /**
         * Returns whether EOF has been signaled for the current read batch.
         *
         * @return {@code true} if EOF was detected
         */
        boolean isReadEOF() {
            return readEOF;
        }

        /** {@inheritDoc} */
        @Override
        public ByteBuf allocate(final ByteBufAllocator alloc) {
            return delegate.allocate(alloc);
        }

        /** {@inheritDoc} */
        @Override
        public int guess() {
            return delegate.guess();
        }

        /**
         * Resets the handle state for a new read batch, clearing the EOF flag.
         *
         * @param config the channel config
         */
        @Override
        public void reset(final ChannelConfig config) {
            readEOF = false;
            delegate.reset(config);
        }

        /** {@inheritDoc} */
        @Override
        public void incMessagesRead(final int numMessages) {
            delegate.incMessagesRead(numMessages);
        }

        /** {@inheritDoc} */
        @Override
        public void lastBytesRead(final int bytes) {
            delegate.lastBytesRead(bytes);
        }

        /** {@inheritDoc} */
        @Override
        public int lastBytesRead() {
            return delegate.lastBytesRead();
        }

        /** {@inheritDoc} */
        @Override
        public void attemptedBytesRead(final int bytes) {
            delegate.attemptedBytesRead(bytes);
        }

        /** {@inheritDoc} */
        @Override
        public int attemptedBytesRead() {
            return delegate.attemptedBytesRead();
        }

        /**
         * Returns whether reading should continue. If EOF was signaled, always returns
         * {@code true} to drain remaining kernel-buffered data before reporting shutdown.
         *
         * @return {@code true} if reading should continue
         */
        @Override
        public boolean continueReading() {
            return readEOF || delegate.continueReading();
        }

        /** {@inheritDoc} */
        @Override
        public void readComplete() {
            delegate.readComplete();
        }
    }
}
