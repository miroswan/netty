package io.netty.ffm.macos;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.macos.generated.Errno;
import io.netty.ffm.macos.generated.kevent;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KqueueIOTest {

    @Test
    void createKqueue() {
        final int kq = KqueueIO.kqueue();
        assertTrue(kq >= 0, "kqueue() returned " + kq);
        io.netty.ffm.posix.FileIO.close(kq);
    }

    @Test
    void keventTimeoutReturnsZero() {
        try (Arena arena = Arena.ofConfined()) {
            final int kq = KqueueIO.kqueue();
            assertTrue(kq >= 0);

            final MemorySegment eventlist = kevent.allocateArray(8, arena);
            final MemorySegment timeout = arena.allocate(16);
            timeout.set(java.lang.foreign.ValueLayout.JAVA_LONG, 0, 0L);
            timeout.set(java.lang.foreign.ValueLayout.JAVA_LONG, 8, 0L);

            final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
            final long result = KqueueIO.kevent(
                    kq, MemorySegment.NULL, 0, eventlist, 8, timeout, capturedState);

            assertEquals(0, ErrnoState.unpackResult(result));

            io.netty.ffm.posix.FileIO.close(kq);
        }
    }

    @Test
    void evSetAndReadBack() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment kev = kevent.allocate(arena);

            KqueueIO.evSet(kev, 42, KqueueIO.EVFILT_READ, KqueueIO.EV_ADD, 0, 0L, 99L);

            assertEquals(42, kevent.ident(kev));
            assertEquals(KqueueIO.EVFILT_READ, kevent.filter(kev));
            assertEquals(KqueueIO.EV_ADD, kevent.flags(kev));
            assertEquals(0, kevent.fflags(kev));
            assertEquals(0L, kevent.data(kev));
        }
    }

    @Test
    void indexedArrayAccessors() {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment events = kevent.allocateArray(3, arena);

            KqueueIO.evSet(kevent.asSlice(events, 0), 10, KqueueIO.EVFILT_READ,
                    KqueueIO.EV_ADD, 0, 100L, 0L);
            KqueueIO.evSet(kevent.asSlice(events, 1), 20, KqueueIO.EVFILT_WRITE,
                    KqueueIO.EV_DELETE, 0, 200L, 0L);
            KqueueIO.evSet(kevent.asSlice(events, 2), 30, KqueueIO.EVFILT_TIMER,
                    KqueueIO.EV_ENABLE, 0, 300L, 0L);

            assertEquals(10, KqueueIO.ident(events, 0));
            assertEquals(20, KqueueIO.ident(events, 1));
            assertEquals(30, KqueueIO.ident(events, 2));

            assertEquals(KqueueIO.EVFILT_READ, KqueueIO.filter(events, 0));
            assertEquals(KqueueIO.EVFILT_WRITE, KqueueIO.filter(events, 1));
            assertEquals(KqueueIO.EVFILT_TIMER, KqueueIO.filter(events, 2));

            assertEquals(KqueueIO.EV_ADD, KqueueIO.flags(events, 0));
            assertEquals(KqueueIO.EV_DELETE, KqueueIO.flags(events, 1));
            assertEquals(KqueueIO.EV_ENABLE, KqueueIO.flags(events, 2));

            assertEquals(100L, KqueueIO.data(events, 0));
            assertEquals(200L, KqueueIO.data(events, 1));
            assertEquals(300L, KqueueIO.data(events, 2));
        }
    }

    @Test
    void userEventRoundTrip() {
        try (Arena arena = Arena.ofConfined()) {
            final int kq = KqueueIO.kqueue();
            assertTrue(kq >= 0);

            final MemorySegment changelist = kevent.allocateArray(1, arena);
            KqueueIO.evSet(kevent.asSlice(changelist, 0), 1, KqueueIO.EVFILT_USER,
                    KqueueIO.EV_ADD, 0, 0L, 0L);

            final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
            final MemorySegment timeout = arena.allocate(16);

            long result = KqueueIO.kevent(
                    kq, changelist, 1, MemorySegment.NULL, 0, timeout, capturedState);
            assertEquals(0, ErrnoState.unpackResult(result));

            KqueueIO.evSet(kevent.asSlice(changelist, 0), 1, KqueueIO.EVFILT_USER,
                    (short) 0, KqueueIO.NOTE_TRIGGER, 0L, 0L);

            result = KqueueIO.kevent(
                    kq, changelist, 1, MemorySegment.NULL, 0, timeout, capturedState);
            assertEquals(0, ErrnoState.unpackResult(result));

            final MemorySegment eventlist = kevent.allocateArray(1, arena);
            timeout.set(java.lang.foreign.ValueLayout.JAVA_LONG, 0, 0L);
            timeout.set(java.lang.foreign.ValueLayout.JAVA_LONG, 8, 0L);

            result = KqueueIO.kevent(
                    kq, MemorySegment.NULL, 0, eventlist, 1, timeout, capturedState);
            assertEquals(1, ErrnoState.unpackResult(result));
            assertEquals(1, KqueueIO.ident(eventlist, 0));
            assertEquals(KqueueIO.EVFILT_USER, KqueueIO.filter(eventlist, 0));

            io.netty.ffm.posix.FileIO.close(kq);
        }
    }

    @Test
    void semanticHelpers() {
        assertTrue(KqueueIO.isInterrupted(ErrnoState.pack(-1, Errno.EINTR())));
        assertFalse(KqueueIO.isError(ErrnoState.pack(-1, Errno.EINTR())));
        assertTrue(KqueueIO.isError(ErrnoState.pack(-1, Errno.EBADF())));
        assertFalse(KqueueIO.isInterrupted(ErrnoState.pack(5, 0)));
    }
}
