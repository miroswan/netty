package io.netty.ffm.macos;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.macos.generated.Event;
import io.netty.ffm.macos.generated.kevent;
import io.netty.ffm.posix.FileIO;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-threaded kqueue event loop that polls for I/O readiness and dispatches
 * events to registered {@link EventHandler} instances. Implements {@link Runnable}
 * so the caller controls the thread.
 *
 * <p>All native resources (arena, kqueue fd, event arrays, captured state) are owned
 * by this loop and allocated from a confined arena on the loop thread. Cross-thread
 * interaction is serialized via a {@link ConcurrentLinkedQueue} command queue with
 * coalesced {@code EVFILT_USER} wakeups.
 *
 * <p>Usage:
 * <pre>{@code
 * EventLoop loop = new EventLoop();
 * Thread loopThread = new Thread(loop, "kqueue-loop");
 * loopThread.start();
 *
 * loop.execute(() -> {
 *     EventRegistration reg = loop.register(myHandler);
 *     reg.subscribeRead();
 * });
 *
 * // later...
 * loop.shutdown().join();
 * }</pre>
 */
public final class EventLoop implements Runnable, AutoCloseable {

    private static final int DEFAULT_MAX_EVENTS = 4096;
    private static final int INITIAL_HANDLERS_CAPACITY = 1024;
    private static final long WAKEUP_IDENT = 0;
    private static final long DEFAULT_POLL_TIMEOUT_MS = 100;

    enum State { NEW, RUNNING, SHUTTING_DOWN, TERMINATED }

    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private volatile Thread loopThread;
    private final CompletableFuture<Void> terminationFuture = new CompletableFuture<>();

    private final int kqueueFd;
    private final int maxEvents;
    private final Arena wakeupArena;
    private final MemorySegment wakeupEvent;

    private Arena arena;
    private MemorySegment capturedState;
    private KQueueEventArray changelist;
    private KQueueEventArray eventlist;
    private MemorySegment timeoutBuffer;
    private EventRegistration[] registrations;

    private final Queue<Runnable> commandQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean wakenUp = new AtomicBoolean(false);

    /**
     * Creates a new event loop with the default max events capacity (4096).
     */
    public EventLoop() {
        this(DEFAULT_MAX_EVENTS);
    }

    /**
     * Creates a new event loop with the given max events capacity.
     *
     * @param maxEvents the maximum number of events per poll cycle
     */
    public EventLoop(final int maxEvents) {
        this.maxEvents = maxEvents;
        this.kqueueFd = KqueueIO.kqueue();
        if (kqueueFd < 0) {
            throw new IllegalStateException("Failed to create kqueue");
        }

        this.wakeupArena = Arena.ofShared();
        this.wakeupEvent = kevent.allocate(wakeupArena);
        KqueueIO.evSet(wakeupEvent, WAKEUP_IDENT, KqueueIO.EVFILT_USER,
                (short) 0, KqueueIO.NOTE_TRIGGER, 0L, 0L);

        registerWakeupEvent();
    }

    /**
     * Registers a handler with this event loop. The handler's fd is used as the
     * array index for O(1) dispatch. Must be called from the loop thread or via
     * {@link #execute(Runnable)}.
     *
     * @param handler the event handler to register
     * @return an {@link EventRegistration} for subscription control
     * @throws IllegalStateException if a handler is already registered for this fd
     */
    public EventRegistration register(final EventHandler handler) {
        final int fd = handler.fd();
        ensureCapacity(fd);
        if (registrations[fd] != null) {
            throw new IllegalStateException("Handler already registered for fd: " + fd);
        }
        final EventRegistration registration = new EventRegistration(fd, handler, this);
        registrations[fd] = registration;
        return registration;
    }

    /**
     * Removes the registration for the given fd. Does not close the handler.
     * Must be called from the loop thread.
     *
     * @param fd the file descriptor to deregister
     */
    public void deregister(final int fd) {
        if (fd >= 0 && fd < registrations.length) {
            registrations[fd] = null;
        }
    }

    /**
     * Submits a command for execution on the loop thread. If already on the loop
     * thread, executes immediately. Otherwise queues the command and wakes the loop.
     *
     * @param command the command to execute
     */
    public void execute(final Runnable command) {
        if (inEventLoop()) {
            command.run();
        } else {
            commandQueue.offer(command);
            wakeup();
        }
    }

    /**
     * Returns {@code true} if the calling thread is the event loop thread.
     *
     * @return {@code true} if on the loop thread
     */
    public boolean inEventLoop() {
        return Thread.currentThread() == loopThread;
    }

    /**
     * Wakes the event loop from a blocking kevent call. Uses {@code EVFILT_USER}
     * with {@link AtomicBoolean} CAS to coalesce multiple wakeup requests into
     * a single kernel syscall.
     */
    public void wakeup() {
        if (wakenUp.compareAndSet(false, true)) {
            Event.kevent(kqueueFd, wakeupEvent, 1,
                    MemorySegment.NULL, 0, MemorySegment.NULL);
        }
    }

    /**
     * Runs the event loop on the current thread. Blocks until {@link #shutdown()}
     * is called. Creates the confined arena and all loop-thread resources on entry.
     */
    @Override
    public void run() {
        if (!state.compareAndSet(State.NEW, State.RUNNING)) {
            return;
        }

        loopThread = Thread.currentThread();
        arena = Arena.ofConfined();
        capturedState = arena.allocate(ErrnoState.layout());
        changelist = new KQueueEventArray(arena, maxEvents);
        eventlist = new KQueueEventArray(arena, maxEvents);
        timeoutBuffer = arena.allocate(16);
        registrations = new EventRegistration[INITIAL_HANDLERS_CAPACITY];

        try {
            runLoop();
        } finally {
            closeAllHandlers();
            FileIO.close(kqueueFd);
            arena.close();
            wakeupArena.close();
            state.set(State.TERMINATED);
            terminationFuture.complete(null);
        }
    }

    /**
     * Signals the event loop to shut down gracefully. Returns a future that
     * completes when the loop has terminated and all resources are freed.
     *
     * @return a future that completes on termination
     */
    public CompletableFuture<Void> shutdown() {
        while (true) {
            final State current = state.get();
            if (current == State.SHUTTING_DOWN || current == State.TERMINATED) {
                return terminationFuture;
            }
            if (state.compareAndSet(current, State.SHUTTING_DOWN)) {
                if (current == State.RUNNING) {
                    wakeup();
                } else {
                    terminationFuture.complete(null);
                }
                return terminationFuture;
            }
        }
    }

    /**
     * Blocking shutdown: signals shutdown, waits for termination, and ensures
     * all resources are released. Safe to call from any thread.
     */
    @Override
    public void close() {
        shutdown();
        try {
            terminationFuture.get(5, TimeUnit.SECONDS);
        } catch (final Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the confined arena owned by this event loop. Only accessible from
     * the loop thread. Handlers can use this for buffer allocation.
     *
     * @return the loop's confined arena
     */
    public Arena arena() {
        return arena;
    }

    /**
     * Returns the pre-allocated captured state segment for errno-safe syscalls.
     * Only accessible from the loop thread.
     *
     * @return the capturedState segment
     */
    public MemorySegment capturedState() {
        return capturedState;
    }

    void addChange(final long ident, final short filter, final short flags,
                   final int fflags, final long data) {
        changelist.add(ident, filter, flags, fflags, data, 0L);
    }

    private void registerWakeupEvent() {
        try (Arena setupArena = Arena.ofConfined()) {
            final MemorySegment setupChange = kevent.allocate(setupArena);
            KqueueIO.evSet(setupChange, WAKEUP_IDENT, KqueueIO.EVFILT_USER,
                    (short) (KqueueIO.EV_ADD | KqueueIO.EV_CLEAR), 0, 0L, 0L);
            final MemorySegment setupCaptured = setupArena.allocate(ErrnoState.layout());
            final long result = KqueueIO.kevent(kqueueFd, setupChange, 1,
                    MemorySegment.NULL, 0, MemorySegment.NULL, setupCaptured);
            if (ErrnoState.unpackResult(result) < 0) {
                FileIO.close(kqueueFd);
                throw new IllegalStateException("Failed to register EVFILT_USER wakeup event");
            }
        }
    }

    private void runLoop() {
        while (state.get() == State.RUNNING) {
            drainCommandQueue();

            final boolean hasWork = !commandQueue.isEmpty() || changelist.size() > 0;
            writeTimeout(hasWork ? 0 : DEFAULT_POLL_TIMEOUT_MS);

            final long result = KqueueIO.kevent(kqueueFd, changelist.memory(), changelist.size(),
                    eventlist.memory(), eventlist.capacity(), timeoutBuffer, capturedState);
            changelist.clear();
            wakenUp.set(false);

            if (KqueueIO.isInterrupted(result)) {
                continue;
            }

            final int readyCount = ErrnoState.unpackResult(result);
            if (readyCount > 0) {
                dispatchEvents(readyCount);
            }
        }
    }

    private void dispatchEvents(final int readyCount) {
        for (int i = 0; i < readyCount; i++) {
            final short filter = eventlist.filter(i);
            final short flags = eventlist.flags(i);
            final long ident = eventlist.ident(i);

            if (filter == KqueueIO.EVFILT_USER) {
                drainCommandQueue();
                continue;
            }

            if (ident < 0 || ident >= registrations.length) {
                continue;
            }
            final EventRegistration reg = registrations[(int) ident];
            if (reg == null || !reg.isValid()) {
                continue;
            }

            final EventHandler handler = reg.handler();

            if ((flags & KqueueIO.EV_ERROR) != 0) {
                handler.onError();
                continue;
            }

            if (filter == KqueueIO.EVFILT_READ) {
                handler.onRead();
                if (reg.isValid() && handler.readPending()) {
                    reg.subscribeRead();
                }
            } else if (filter == KqueueIO.EVFILT_WRITE) {
                handler.onWrite();
            }

            if ((flags & KqueueIO.EV_EOF) != 0) {
                final EventRegistration current = registrations[(int) ident];
                if (current != null && current.isValid()) {
                    current.handler().onEof();
                }
            }
        }
    }

    private void drainCommandQueue() {
        Runnable command;
        while ((command = commandQueue.poll()) != null) {
            command.run();
        }
    }

    private void writeTimeout(final long millis) {
        timeoutBuffer.set(ValueLayout.JAVA_LONG, 0, millis / 1000);
        timeoutBuffer.set(ValueLayout.JAVA_LONG, 8, (millis % 1000) * 1_000_000);
    }

    private static final int MAX_ARRAY_CAPACITY = 65536;

    private void ensureCapacity(final int fd) {
        if (fd >= MAX_ARRAY_CAPACITY) {
            throw new IllegalStateException(
                    "File descriptor " + fd + " exceeds maximum supported value of " + MAX_ARRAY_CAPACITY);
        }
        if (registrations == null || fd >= registrations.length) {
            final int newCapacity = Math.min(MAX_ARRAY_CAPACITY,
                    Math.max(fd + 1, registrations == null ? INITIAL_HANDLERS_CAPACITY
                            : registrations.length * 2));
            final EventRegistration[] newRegistrations = new EventRegistration[newCapacity];
            if (registrations != null) {
                System.arraycopy(registrations, 0, newRegistrations, 0, registrations.length);
            }
            registrations = newRegistrations;
        }
    }

    private void closeAllHandlers() {
        if (registrations == null) {
            return;
        }
        for (int i = 0; i < registrations.length; i++) {
            final EventRegistration reg = registrations[i];
            if (reg != null) {
                try {
                    reg.handler().close();
                } catch (final Exception e) {
                    // best effort during shutdown
                }
                registrations[i] = null;
            }
        }
    }
}
