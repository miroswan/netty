package io.netty.ffm.macos;

/**
 * Tracks read iterations within a single {@link EventHandler#onRead()} invocation
 * to prevent a single fd from starving other fds on the same event loop. When the
 * limit is reached, the handler should stop reading and the event loop will re-arm
 * the read subscription on the next tick.
 *
 * <p>With edge-triggered kqueue ({@code EV_CLEAR}), stopping before {@code EAGAIN}
 * means kqueue will not re-fire the event. The handler must call
 * {@link EventRegistration#subscribeRead()} to re-arm, or the event loop will
 * re-arm automatically if {@link #limitReached()} returns {@code true} after dispatch.
 *
 * <p>Call {@link #increment()} after each successful read. Check {@link #shouldContinue()}
 * before each read attempt. Call {@link #reset()} at the start of each {@code onRead()}.
 */
public final class ReadLimiter {

    private final int maxReadsPerCycle;
    private int count;

    /**
     * Creates a new read limiter.
     *
     * @param maxReadsPerCycle the maximum number of read operations per onRead() invocation
     */
    public ReadLimiter(final int maxReadsPerCycle) {
        this.maxReadsPerCycle = maxReadsPerCycle;
        this.count = 0;
    }

    /**
     * Returns {@code true} if the handler may attempt another read.
     *
     * @return {@code true} if below the limit
     */
    public boolean shouldContinue() {
        return count < maxReadsPerCycle;
    }

    /**
     * Records a successful read. Call after each read that returned data.
     */
    public void increment() {
        count++;
    }

    /**
     * Returns {@code true} if the limit was reached, meaning the fd may still have
     * data and the read subscription should be re-armed.
     *
     * @return {@code true} if the handler stopped due to the limit, not EAGAIN
     */
    public boolean limitReached() {
        return count >= maxReadsPerCycle;
    }

    /**
     * Resets the counter. Call at the start of each {@code onRead()} invocation.
     */
    public void reset() {
        count = 0;
    }
}
