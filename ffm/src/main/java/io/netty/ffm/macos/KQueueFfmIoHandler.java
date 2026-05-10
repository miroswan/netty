package io.netty.ffm.macos;

import io.netty.channel.DefaultSelectStrategyFactory;
import io.netty.channel.IoHandle;
import io.netty.channel.IoHandler;
import io.netty.channel.IoHandlerContext;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.IoOps;
import io.netty.channel.IoRegistration;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SelectStrategyFactory;
import io.netty.channel.kqueue.KQueueIoEvent;
import io.netty.channel.kqueue.KQueueIoHandle;
import io.netty.channel.kqueue.KQueueIoOps;
import io.netty.ffm.ErrnoState;
import io.netty.ffm.macos.generated.Event;
import io.netty.ffm.macos.generated.kevent;
import io.netty.ffm.posix.FileIO;
import io.netty.util.IntSupplier;
import io.netty.util.collection.LongObjectHashMap;
import io.netty.util.collection.LongObjectMap;
import io.netty.util.concurrent.ThreadAwareExecutor;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static java.lang.Math.min;

/**
 * {@link IoHandler} implementation backed by kqueue via the Java Foreign Function and Memory
 * (FFM) API. This replaces the JNI-based {@code KQueueIoHandler} with zero native library
 * dependencies — all syscalls go through {@link java.lang.foreign.Linker} downcall handles.
 *
 * <p>This handler is compatible with any {@link KQueueIoHandle} implementation. Channels
 * submit interest via {@link KQueueIoOps} and receive readiness notifications via
 * {@link KQueueIoEvent}, identical to the JNI-based handler.
 *
 * <p>All native memory (changelist, eventlist, captured state, timeout buffer) is allocated
 * from a confined {@link Arena} created during construction. The arena's confinement aligns
 * with Netty's single-threaded executor model — all {@code run()}, {@code register()}, and
 * {@code destroy()} calls happen on the same executor thread.
 *
 * <p>Wakeup uses {@code EVFILT_USER} with a pre-allocated kevent stored in a shared arena,
 * allowing cross-thread wakeup without confined arena violations.
 */
public final class KQueueFfmIoHandler implements IoHandler, AutoCloseable {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(KQueueFfmIoHandler.class);
    private static final AtomicIntegerFieldUpdater<KQueueFfmIoHandler> WAKEN_UP_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(KQueueFfmIoHandler.class, "wakenUp");
    private static final long KQUEUE_WAKE_UP_IDENT = 0;
    private static final int KQUEUE_MAX_TIMEOUT_SECONDS = 86399;

    private final boolean allowGrowing;
    private final int kqueueFd;
    private final SelectStrategy selectStrategy;
    private final ThreadAwareExecutor executor;
    private final Queue<DefaultFfmKqueueIoRegistration> cancelledRegistrations = new ArrayDeque<>();
    private final LongObjectMap<DefaultFfmKqueueIoRegistration> registrations = new LongObjectHashMap<>(4096);
    private final IntSupplier selectNowSupplier = this::kqueueWaitNow;

    // Shared arena: wakeup() is called from any thread (cross-thread by design), so the
    // wakeup kevent segment must be accessible without confinement. A shared arena permits
    // concurrent access from both the executor thread and external callers.
    private final Arena wakeupArena = Arena.ofShared();
    private final MemorySegment wakeupEvent;

    // Confined arena: all event loop resources (changelist, eventlist, capturedState,
    // timeoutBuffer) are accessed exclusively from the executor thread. A confined arena
    // avoids the synchronization overhead of shared arenas and enforces single-threaded
    // ownership at the JVM level, aligning with Netty's single-threaded executor model.
    private final Arena arena = Arena.ofConfined();
    private KQueueEventArray changelist;
    private KQueueEventArray eventlist;
    private MemorySegment capturedState;
    private MemorySegment timeoutBuffer;

    private long nextId;
    private volatile int wakenUp;

    /**
     * Generates a unique registration ID that does not collide with existing registrations
     * or the reserved wakeup ident. IDs are monotonically increasing and wrap around
     * {@link Long#MAX_VALUE} exactly once before throwing.
     *
     * @return the next available registration ID
     * @throws IllegalStateException if all possible IDs are exhausted
     */
    private long generateNextId() {
        boolean reset = false;
        for (;;) {
            if (nextId == Long.MAX_VALUE) {
                if (reset) {
                    throw new IllegalStateException("All possible ids in use");
                }
                reset = true;
            }
            nextId++;
            if (nextId == KQUEUE_WAKE_UP_IDENT) {
                continue;
            }
            if (!registrations.containsKey(nextId)) {
                return nextId;
            }
        }
    }

    /**
     * Returns a new {@link IoHandlerFactory} that creates {@link KQueueFfmIoHandler} instances
     * with default settings (growable eventlist starting at 4096, default select strategy).
     *
     * @return a factory for FFM-backed kqueue handlers
     */
    public static IoHandlerFactory newFactory() {
        return newFactory(0, DefaultSelectStrategyFactory.INSTANCE);
    }

    /**
     * Returns a new {@link IoHandlerFactory} that creates {@link KQueueFfmIoHandler} instances
     * with the specified configuration.
     *
     * @param maxEvents the fixed eventlist capacity, or 0 for a growable eventlist starting at 4096
     * @param selectStrategyFactory the factory for creating the {@link SelectStrategy} used by each handler
     * @return a factory for FFM-backed kqueue handlers
     */
    public static IoHandlerFactory newFactory(final int maxEvents,
                                              final SelectStrategyFactory selectStrategyFactory) {
        return new IoHandlerFactory() {
            @Override
            public IoHandler newHandler(final ThreadAwareExecutor executor) {
                return new KQueueFfmIoHandler(executor, maxEvents, selectStrategyFactory.newSelectStrategy());
            }

            @Override
            public boolean isChangingThreadSupported() {
                return true;
            }
        };
    }

    /**
     * Constructs a new handler bound to the given executor. Creates the kqueue file descriptor,
     * allocates all native memory from a confined arena, and registers the {@code EVFILT_USER}
     * wakeup filter.
     *
     * @param executor the executor thread this handler runs on
     * @param maxEvents the eventlist capacity (0 means growable from 4096)
     * @param strategy the select strategy controlling blocking behavior
     * @throws IllegalStateException if kqueue creation or wakeup registration fails
     */
    private KQueueFfmIoHandler(final ThreadAwareExecutor executor, int maxEvents,
                               final SelectStrategy strategy) {
        this.executor = executor;
        this.selectStrategy = strategy;
        this.kqueueFd = KqueueIO.kqueue();
        if (kqueueFd < 0) {
            throw new IllegalStateException("Failed to create kqueue fd");
        }
        if (maxEvents == 0) {
            allowGrowing = true;
            maxEvents = 4096;
        } else {
            allowGrowing = false;
        }

        this.wakeupEvent = kevent.allocate(wakeupArena);
        KqueueIO.evSet(wakeupEvent, KQUEUE_WAKE_UP_IDENT, KqueueIO.EVFILT_USER,
                (short) 0, KqueueIO.NOTE_TRIGGER, 0L, 0L);

        this.capturedState = arena.allocate(ErrnoState.layout());
        this.changelist = new KQueueEventArray(arena, maxEvents);
        this.eventlist = new KQueueEventArray(arena, maxEvents);
        this.timeoutBuffer = arena.allocate(16);

        registerWakeupFilter();
    }

    /**
     * Registers the {@code EVFILT_USER} filter with the kqueue so that {@link #wakeup()} can
     * interrupt a blocking {@code kevent()} call. Uses a temporary confined arena for the
     * setup changelist since the main arena may not yet be accessible from the executor thread.
     *
     * @throws IllegalStateException if the registration syscall fails
     */
    private void registerWakeupFilter() {
        try (final Arena setupArena = Arena.ofConfined()) {
            final MemorySegment setupChange = kevent.allocate(setupArena);
            KqueueIO.evSet(setupChange, KQUEUE_WAKE_UP_IDENT, KqueueIO.EVFILT_USER,
                    (short) (KqueueIO.EV_ADD | KqueueIO.EV_CLEAR), 0, 0L, 0L);
            final MemorySegment setupCaptured = setupArena.allocate(ErrnoState.layout());
            final long result = KqueueIO.kevent(kqueueFd, setupChange, 1,
                    MemorySegment.NULL, 0, MemorySegment.NULL, setupCaptured);
            if (ErrnoState.unpackResult(result) < 0) {
                FileIO.close(kqueueFd);
                arena.close();
                wakeupArena.close();
                throw new IllegalStateException("Failed to register EVFILT_USER wakeup event");
            }
        }
    }

    /**
     * Wakes the handler from a blocking {@code kevent()} call by triggering the
     * {@code EVFILT_USER} event. This method is safe to call from any thread.
     *
     * <p>Uses a CAS on {@code wakenUp} to coalesce multiple concurrent wakeup requests
     * into a single syscall. Only the thread that transitions {@code wakenUp} from 0 to 1
     * performs the actual kevent trigger.
     */
    @Override
    public void wakeup() {
        if (!executor.isExecutorThread(Thread.currentThread())
                && WAKEN_UP_UPDATER.compareAndSet(this, 0, 1)) {
            wakeup0();
        }
    }

    /**
     * Performs the actual {@code EVFILT_USER} trigger syscall using the pre-allocated
     * wakeup event segment from the shared arena.
     */
    private void wakeup0() {
        Event.kevent(kqueueFd, wakeupEvent, 1, MemorySegment.NULL, 0, MemorySegment.NULL);
    }

    /**
     * Executes a single pass of the I/O loop: determines whether to block, polls for
     * ready events via {@code kevent()}, dispatches them to registered handles, and
     * processes any cancelled registrations.
     *
     * <p>The {@link IoHandlerContext} controls blocking behavior — when tasks are pending,
     * the handler performs a non-blocking poll. Otherwise it blocks up to the next scheduled
     * task deadline.
     *
     * <p>If the eventlist was fully consumed and growing is enabled, the eventlist capacity
     * is doubled for the next poll.
     *
     * @param context provides deadline and blocking information from the event loop
     * @return the number of handles for which I/O was processed
     */
    @Override
    public int run(final IoHandlerContext context) {
        int handled = 0;
        try {
            int strategy = selectStrategy.calculateStrategy(selectNowSupplier, !context.canBlock());
            switch (strategy) {
                case SelectStrategy.CONTINUE:
                    if (context.shouldReportActiveIoTime()) {
                        context.reportActiveIoTime(0);
                    }
                    return 0;

                case SelectStrategy.BUSY_WAIT:
                    // fall-through to SELECT since busy-wait is not supported with kqueue

                case SelectStrategy.SELECT:
                    strategy = kqueueWait(context, WAKEN_UP_UPDATER.getAndSet(this, 0) == 1);
                    if (wakenUp == 1) {
                        wakeup0();
                    }
                    // fall-through
                default:
            }

            if (strategy > 0) {
                handled = strategy;
                if (context.shouldReportActiveIoTime()) {
                    final long activeIoStartTimeNanos = System.nanoTime();
                    processReady(strategy);
                    final long activeIoEndTimeNanos = System.nanoTime();
                    context.reportActiveIoTime(activeIoEndTimeNanos - activeIoStartTimeNanos);
                } else {
                    processReady(strategy);
                }
            } else if (context.shouldReportActiveIoTime()) {
                context.reportActiveIoTime(0);
            }

            if (allowGrowing && strategy == eventlist.capacity()) {
                eventlist.realloc();
            }
        } catch (final Error e) {
            throw e;
        } catch (final Throwable t) {
            handleLoopException(t);
        } finally {
            processCancelledRegistrations();
        }
        return handled;
    }

    /**
     * Blocks on {@code kevent()} with a timeout derived from the handler context's next
     * scheduled deadline. If a wakeup occurred before this call and there are tasks pending,
     * performs a non-blocking poll instead.
     *
     * @param context the handler context providing deadline information
     * @param oldWakeup true if a wakeup was pending before this call
     * @return the number of ready events
     */
    private int kqueueWait(final IoHandlerContext context, final boolean oldWakeup) {
        if (oldWakeup && !context.canBlock()) {
            return kqueueWaitNow();
        }

        final long totalDelay = context.delayNanos(System.nanoTime());
        final int delaySeconds = (int) min(totalDelay / 1_000_000_000L, KQUEUE_MAX_TIMEOUT_SECONDS);
        final int delayNanos = (int) (totalDelay % 1_000_000_000L);
        return kqueueWait(delaySeconds, delayNanos);
    }

    /**
     * Performs a non-blocking poll by calling {@code kevent()} with a zero timeout.
     *
     * @return the number of ready events
     */
    private int kqueueWaitNow() {
        return kqueueWait(0, 0);
    }

    /**
     * Calls the {@code kevent()} syscall via FFM, submitting pending changelist entries and
     * receiving ready events into the eventlist. The changelist is cleared after every call
     * regardless of outcome.
     *
     * <p>Writes the timeout as a {@code struct timespec} (seconds at offset 0, nanoseconds
     * at offset 8) into the pre-allocated timeout buffer.
     *
     * @param timeoutSec the timeout seconds component
     * @param timeoutNs the timeout nanoseconds component
     * @return the number of ready events, or 0 if interrupted
     * @throws IllegalStateException if kevent fails with a non-EINTR error
     */
    private int kqueueWait(final int timeoutSec, final int timeoutNs) {
        timeoutBuffer.set(ValueLayout.JAVA_LONG, 0, timeoutSec);
        timeoutBuffer.set(ValueLayout.JAVA_LONG, 8, timeoutNs);

        final long result = KqueueIO.kevent(kqueueFd, changelist.memory(), changelist.size(),
                eventlist.memory(), eventlist.capacity(), timeoutBuffer, capturedState);
        changelist.clear();

        if (KqueueIO.isInterrupted(result)) {
            return 0;
        }
        if (KqueueIO.isError(result)) {
            throw new IllegalStateException(
                    "kevent failed with errno: " + ErrnoState.unpackErrno(result));
        }
        return ErrnoState.unpackResult(result);
    }

    /**
     * Iterates over the ready events returned by {@code kevent()} and dispatches each to
     * its registered handle. Skips {@code EVFILT_USER} events (internal wakeups) and
     * {@code EV_ERROR} flagged events (stale fd errors). Looks up registrations by the
     * {@code udata} field which stores the unique registration ID.
     *
     * @param ready the number of ready events to process
     */
    private void processReady(final int ready) {
        for (int i = 0; i < ready; i++) {
            final short filter = eventlist.filter(i);
            final short flags = eventlist.flags(i);
            final long ident = eventlist.ident(i);

            if (filter == KqueueIO.EVFILT_USER || (flags & KqueueIO.EV_ERROR) != 0) {
                continue;
            }

            final long id = eventlist.udata(i);
            final DefaultFfmKqueueIoRegistration registration = registrations.get(id);
            if (registration == null) {
                logger.warn("events[{}]=[{}, {}, {}] had no registration!", i, ident, id, filter);
                continue;
            }
            registration.handle((int) ident, filter, flags, eventlist.fflags(i), eventlist.data(i), id);
        }
    }

    /**
     * Drains the cancelled registrations queue, removing each from the registrations map
     * and notifying the handle that it has been unregistered. Called at the end of every
     * {@link #run} pass to ensure cancelled handles are cleaned up after all pending events
     * for them have been processed.
     */
    private void processCancelledRegistrations() {
        for (;;) {
            final DefaultFfmKqueueIoRegistration cancelledRegistration = cancelledRegistrations.poll();
            if (cancelledRegistration == null) {
                return;
            }
            final DefaultFfmKqueueIoRegistration removed = registrations.remove(cancelledRegistration.id);
            assert removed == cancelledRegistration;
            removed.handle.unregistered();
        }
    }

    /**
     * Prepares for destruction by performing a non-blocking poll to flush pending events,
     * then closing all active registrations. This may be called multiple times before
     * {@link #destroy()}.
     */
    @Override
    public void prepareToDestroy() {
        kqueueWaitNow();
        final DefaultFfmKqueueIoRegistration[] copy =
                registrations.values().toArray(new DefaultFfmKqueueIoRegistration[0]);
        for (final DefaultFfmKqueueIoRegistration reg : copy) {
            reg.close();
        }
        processCancelledRegistrations();
    }

    /**
     * Releases all resources held by this handler: closes the kqueue file descriptor,
     * frees the confined arena (which releases changelist, eventlist, captured state, and
     * timeout buffer), and frees the shared wakeup arena.
     *
     * <p>Must be called exactly once after {@link #prepareToDestroy()}. Using the handler
     * after this call results in undefined behavior.
     */
    @Override
    public void destroy() {
        try {
            FileIO.close(kqueueFd);
        } finally {
            arena.close();
            wakeupArena.close();
        }
    }

    /**
     * Equivalent to {@link #destroy()}. Allows use in try-with-resources.
     */
    @Override
    public void close() {
        destroy();
    }

    /**
     * Registers a {@link KQueueIoHandle} with this handler. Assigns a unique ID to the
     * registration and stores it in the registrations map. The handle's
     * {@link IoHandle#registered()} callback is invoked before returning.
     *
     * <p>The assigned ID is stored in the {@code udata} field of subsequent kevent entries,
     * enabling O(1) dispatch in {@link #processReady}.
     *
     * @param handle the handle to register (must implement {@link KQueueIoHandle})
     * @return the registration for submitting ops and managing the handle's lifecycle
     * @throws IllegalArgumentException if the handle type is incompatible or uses the reserved ident
     * @throws IllegalStateException if the generated ID collides (should not happen in practice)
     */
    @Override
    public IoRegistration register(final IoHandle handle) {
        final KQueueIoHandle kqueueHandle = cast(handle);
        if (kqueueHandle.ident() == KQUEUE_WAKE_UP_IDENT) {
            throw new IllegalArgumentException(
                    "ident " + KQUEUE_WAKE_UP_IDENT + " is reserved for internal usage");
        }

        final DefaultFfmKqueueIoRegistration registration =
                new DefaultFfmKqueueIoRegistration(executor, kqueueHandle);
        final DefaultFfmKqueueIoRegistration old = registrations.put(registration.id, registration);
        if (old != null) {
            registrations.put(old.id, old);
            throw new IllegalStateException();
        }
        handle.registered();
        return registration;
    }

    /**
     * Returns whether the given handle type is compatible with this handler. Only
     * {@link KQueueIoHandle} implementations are accepted.
     *
     * @param handleType the handle class to check
     * @return {@code true} if the type implements {@link KQueueIoHandle}
     */
    @Override
    public boolean isCompatible(final Class<? extends IoHandle> handleType) {
        return KQueueIoHandle.class.isAssignableFrom(handleType);
    }

    /**
     * Casts a generic {@link IoHandle} to {@link KQueueIoHandle}, throwing if incompatible.
     *
     * @param handle the handle to cast
     * @return the handle as a {@link KQueueIoHandle}
     * @throws IllegalArgumentException if the handle does not implement {@link KQueueIoHandle}
     */
    private static KQueueIoHandle cast(final IoHandle handle) {
        if (handle instanceof KQueueIoHandle) {
            return (KQueueIoHandle) handle;
        }
        throw new IllegalArgumentException(
                "IoHandle of type " + handle.getClass().getSimpleName() + " not supported");
    }

    /**
     * Casts a generic {@link IoOps} to {@link KQueueIoOps}, throwing if incompatible.
     *
     * @param ops the ops to cast
     * @return the ops as a {@link KQueueIoOps}
     * @throws IllegalArgumentException if the ops does not implement {@link KQueueIoOps}
     */
    private static KQueueIoOps cast(final IoOps ops) {
        if (ops instanceof KQueueIoOps) {
            return (KQueueIoOps) ops;
        }
        throw new IllegalArgumentException(
                "IoOps of type " + ops.getClass().getSimpleName() + " not supported");
    }

    /**
     * Logs an unexpected exception from the event loop and sleeps for 1 second to prevent
     * tight-loop CPU spin in case of persistent errors.
     *
     * @param t the exception that occurred during the loop iteration
     */
    private static void handleLoopException(final Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);
        try {
            Thread.sleep(1000);
        } catch (final InterruptedException e) {
            // Ignore.
        }
    }

    /**
     * Per-handle registration that tracks the handle's lifecycle, queues kqueue filter
     * changes via the handler's changelist, and dispatches ready events back to the handle.
     *
     * <p>Each registration has a unique {@link #id} stored in the kevent {@code udata} field
     * for O(1) dispatch lookup. Cancellation is deferred — the registration is queued and
     * processed after all events in the current loop pass are dispatched, preventing
     * concurrent modification of the registrations map during iteration.
     */
    private final class DefaultFfmKqueueIoRegistration implements IoRegistration {

        private final AtomicBoolean canceled = new AtomicBoolean();
        private boolean cancellationPending;

        final KQueueIoHandle handle;
        final long id;
        private final ThreadAwareExecutor executor;

        /**
         * Creates a new registration for the given handle, assigning a unique ID.
         *
         * @param executor the executor this registration is bound to
         * @param handle the kqueue handle being registered
         */
        DefaultFfmKqueueIoRegistration(final ThreadAwareExecutor executor,
                                       final KQueueIoHandle handle) {
            this.executor = executor;
            this.handle = handle;
            this.id = generateNextId();
        }

        /**
         * Returns {@code null}. The attachment mechanism is reserved for pass 2 when FFM
         * channel classes will provide an FFM-backed {@code IovArray} here for vectored writes.
         *
         * @return {@code null}
         */
        @SuppressWarnings("unchecked")
        @Override
        public <T> T attachment() {
            return null;
        }

        /**
         * Submits a kqueue filter change for this registration's handle. The change is
         * queued in the handler's changelist and will be submitted to the kernel on the
         * next {@code kevent()} call.
         *
         * <p>If called from a thread other than the executor thread, the change is
         * scheduled for execution on the executor thread.
         *
         * @param ops the kqueue operations to submit (must be {@link KQueueIoOps})
         * @return 0 on success, -1 if the registration has been cancelled
         */
        @Override
        public long submit(final IoOps ops) {
            final KQueueIoOps kQueueIoOps = cast(ops);
            if (!isValid()) {
                return -1;
            }
            final short filter = kQueueIoOps.filter();
            final short flags = kQueueIoOps.flags();
            final int fflags = kQueueIoOps.fflags();
            final long data = kQueueIoOps.data();
            if (executor.isExecutorThread(Thread.currentThread())) {
                evSet(filter, flags, fflags, data);
            } else {
                executor.execute(() -> evSet(filter, flags, fflags, data));
            }
            return 0;
        }

        /**
         * Dispatches a ready event to the handle by constructing a {@link KQueueIoEvent}
         * and calling {@link IoHandle#handle(IoRegistration, io.netty.channel.IoEvent)}.
         * Skips dispatch if cancellation is pending.
         *
         * @param ident the file descriptor that triggered the event
         * @param filter the event filter (EVFILT_READ, EVFILT_WRITE, etc.)
         * @param flags the event flags (EV_EOF, EV_ERROR, etc.)
         * @param fflags filter-specific flags (NOTE_RDHUP, etc.)
         * @param data filter-specific data (bytes available, etc.)
         * @param udata the registration ID stored in the kevent
         */
        void handle(final int ident, final short filter, final short flags,
                    final int fflags, final long data, final long udata) {
            if (cancellationPending) {
                return;
            }
            final KQueueIoEvent event = KQueueIoEvent.newEvent(ident, filter, flags, fflags, data, udata);
            handle.handle(this, event);
        }

        /**
         * Adds a kevent change entry to the handler's changelist for this registration.
         * The entry uses the handle's ident as the event identifier and this registration's
         * ID as the udata field for dispatch lookup.
         *
         * @param filter the kqueue filter to register/modify
         * @param flags the action flags (EV_ADD, EV_DELETE, etc.)
         * @param fflags filter-specific flags
         * @param data filter-specific data
         */
        private void evSet(final short filter, final short flags, final int fflags, final long data) {
            if (cancellationPending) {
                return;
            }
            changelist.add(handle.ident(), filter, flags, fflags, data, id);
        }

        /**
         * Returns whether this registration is still active. Once {@link #cancel()} is
         * called, this returns {@code false}.
         *
         * @return {@code true} if the registration has not been cancelled
         */
        @Override
        public boolean isValid() {
            return !canceled.get();
        }

        /**
         * Cancels this registration. The actual removal from the registrations map is
         * deferred until the end of the current event loop pass via
         * {@link #processCancelledRegistrations()}, preventing concurrent modification
         * during event dispatch.
         *
         * <p>Thread-safe: if called from a non-executor thread, the cancellation is
         * scheduled on the executor.
         *
         * @return {@code true} if this call performed the cancellation, {@code false} if
         *         already cancelled
         */
        @Override
        public boolean cancel() {
            if (!canceled.compareAndSet(false, true)) {
                return false;
            }
            if (executor.isExecutorThread(Thread.currentThread())) {
                cancel0();
            } else {
                executor.execute(this::cancel0);
            }
            return true;
        }

        /**
         * Marks cancellation as pending and enqueues this registration for deferred removal.
         */
        private void cancel0() {
            cancellationPending = true;
            cancelledRegistrations.offer(this);
        }

        /**
         * Cancels this registration and closes the underlying handle. Used during
         * {@link #prepareToDestroy()} to forcibly clean up all active registrations.
         */
        void close() {
            cancel();
            try {
                handle.close();
            } catch (final Exception e) {
                logger.debug("Exception during closing " + handle, e);
            }
        }
    }
}
