package io.netty.ffm.macos;

/**
 * Contract for handlers registered with an {@link EventLoop}. The event loop calls
 * these methods when kqueue reports I/O readiness on the handler's file descriptor.
 *
 * <p>All methods are called on the event loop thread. With edge-triggered kqueue
 * ({@code EV_CLEAR}), implementations should read in a loop until {@code EAGAIN}
 * or until a {@link ReadLimiter} signals to stop. If the handler yields before
 * {@code EAGAIN}, it must return {@code true} from {@link #readPending()} so the
 * event loop re-arms the read subscription on the next tick.
 */
public interface EventHandler extends AutoCloseable {

    /**
     * Returns the file descriptor this handler manages.
     *
     * @return the raw fd
     */
    int fd();

    /**
     * Called when the fd is ready for reading. The implementation should read in a
     * loop until {@code EAGAIN} or until a read limit is reached.
     */
    void onRead();

    /**
     * Called when the fd is ready for writing. Typically used to resume a partial
     * write or detect connect completion.
     */
    void onWrite();

    /**
     * Called when {@code EV_EOF} is detected on the fd, indicating the peer has
     * closed its end of the connection.
     */
    void onEof();

    /**
     * Called when {@code EV_ERROR} is detected on the fd.
     */
    void onError();

    /**
     * Returns {@code true} if the handler stopped reading before {@code EAGAIN}
     * due to a read limit. The event loop uses this to re-arm the read subscription
     * on the next tick, since edge-triggered kqueue will not re-fire without it.
     *
     * @return {@code true} if the fd may still have readable data
     */
    boolean readPending();

    /**
     * Closes this handler and releases its resources, including closing the fd.
     */
    @Override
    void close();
}
