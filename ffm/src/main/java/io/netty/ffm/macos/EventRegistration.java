package io.netty.ffm.macos;

/**
 * Tracks a registered file descriptor and provides subscription control back into
 * the {@link EventLoop}. Created by {@link EventLoop#register(EventHandler)} and
 * returned to the caller for managing read/write interest.
 *
 * <p>All methods must be called from the event loop thread.
 */
public final class EventRegistration {

    private final int fd;
    private final EventHandler handler;
    private final EventLoop loop;
    private boolean valid;

    EventRegistration(final int fd, final EventHandler handler, final EventLoop loop) {
        this.fd = fd;
        this.handler = handler;
        this.loop = loop;
        this.valid = true;
    }

    /**
     * Returns the registered file descriptor.
     *
     * @return the raw fd
     */
    public int fd() {
        return fd;
    }

    /**
     * Returns the handler associated with this registration.
     *
     * @return the event handler
     */
    public EventHandler handler() {
        return handler;
    }

    /**
     * Returns {@code true} if this registration is still active.
     *
     * @return {@code true} if not cancelled
     */
    public boolean isValid() {
        return valid;
    }

    /**
     * Subscribes to read events ({@code EVFILT_READ} with {@code EV_ADD|EV_CLEAR}).
     */
    public void subscribeRead() {
        if (valid) {
            loop.addChange(fd, KqueueIO.EVFILT_READ,
                    (short) (KqueueIO.EV_ADD | KqueueIO.EV_CLEAR), 0, 0L);
        }
    }

    /**
     * Unsubscribes from read events ({@code EVFILT_READ} with {@code EV_DELETE}).
     */
    public void unsubscribeRead() {
        if (valid) {
            loop.addChange(fd, KqueueIO.EVFILT_READ, KqueueIO.EV_DELETE, 0, 0L);
        }
    }

    /**
     * Subscribes to write events ({@code EVFILT_WRITE} with {@code EV_ADD|EV_CLEAR}).
     */
    public void subscribeWrite() {
        if (valid) {
            loop.addChange(fd, KqueueIO.EVFILT_WRITE,
                    (short) (KqueueIO.EV_ADD | KqueueIO.EV_CLEAR), 0, 0L);
        }
    }

    /**
     * Unsubscribes from write events ({@code EVFILT_WRITE} with {@code EV_DELETE}).
     */
    public void unsubscribeWrite() {
        if (valid) {
            loop.addChange(fd, KqueueIO.EVFILT_WRITE, KqueueIO.EV_DELETE, 0, 0L);
        }
    }

    /**
     * Cancels this registration. Pushes {@code EV_DELETE} for both read and write
     * filters to prevent spurious wakeups from orphaned kqueue subscriptions, then
     * removes the handler from the event loop's registry. Does not close the handler.
     */
    public void cancel() {
        if (valid) {
            valid = false;
            loop.addChange(fd, KqueueIO.EVFILT_READ, KqueueIO.EV_DELETE, 0, 0L);
            loop.addChange(fd, KqueueIO.EVFILT_WRITE, KqueueIO.EV_DELETE, 0, 0L);
            loop.deregister(fd);
        }
    }
}
