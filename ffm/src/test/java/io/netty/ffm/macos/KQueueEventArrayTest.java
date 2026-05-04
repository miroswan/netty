package io.netty.ffm.macos;

import io.netty.ffm.ErrnoState;
import io.netty.ffm.posix.FileIO;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KQueueEventArrayTest {

    @Test
    void addAndReadBack() {
        try (Arena arena = Arena.ofConfined()) {
            final KQueueEventArray array = new KQueueEventArray(arena, 4);
            array.add(10, KqueueIO.EVFILT_READ, KqueueIO.EV_ADD, 0, 0L, 99L);
            array.add(20, KqueueIO.EVFILT_WRITE, KqueueIO.EV_DELETE, 0, 0L, 100L);

            assertEquals(2, array.size());
            assertEquals(4, array.capacity());

            assertEquals(10, array.ident(0));
            assertEquals(KqueueIO.EVFILT_READ, array.filter(0));
            assertEquals(KqueueIO.EV_ADD, array.flags(0));

            assertEquals(20, array.ident(1));
            assertEquals(KqueueIO.EVFILT_WRITE, array.filter(1));
            assertEquals(KqueueIO.EV_DELETE, array.flags(1));
        }
    }

    @Test
    void clearResetsSize() {
        try (Arena arena = Arena.ofConfined()) {
            final KQueueEventArray array = new KQueueEventArray(arena, 4);
            array.add(1, KqueueIO.EVFILT_READ, KqueueIO.EV_ADD, 0, 0L, 0L);
            array.add(2, KqueueIO.EVFILT_READ, KqueueIO.EV_ADD, 0, 0L, 0L);
            assertEquals(2, array.size());

            array.clear();
            assertEquals(0, array.size());
            assertEquals(4, array.capacity());
        }
    }

    @Test
    void growDoublesCapacity() {
        try (Arena arena = Arena.ofConfined()) {
            final KQueueEventArray array = new KQueueEventArray(arena, 2);
            array.add(1, KqueueIO.EVFILT_READ, KqueueIO.EV_ADD, 0, 0L, 0L);
            array.add(2, KqueueIO.EVFILT_WRITE, KqueueIO.EV_ADD, 0, 0L, 0L);
            assertEquals(2, array.capacity());

            array.add(3, KqueueIO.EVFILT_TIMER, KqueueIO.EV_ADD, 0, 0L, 0L);
            assertEquals(4, array.capacity());
            assertEquals(3, array.size());

            assertEquals(1, array.ident(0));
            assertEquals(2, array.ident(1));
            assertEquals(3, array.ident(2));
        }
    }

    @Test
    void useAsChangelistWithKqueue() {
        final int kq = KqueueIO.kqueue();
        assertTrue(kq >= 0);

        try (Arena arena = Arena.ofConfined()) {
            final KQueueEventArray changelist = new KQueueEventArray(arena, 4);
            final KQueueEventArray eventlist = new KQueueEventArray(arena, 4);

            changelist.add(1, KqueueIO.EVFILT_USER, KqueueIO.EV_ADD, 0, 0L, 0L);

            final MemorySegment capturedState = arena.allocate(ErrnoState.layout());
            final MemorySegment timeout = arena.allocate(16);

            long result = KqueueIO.kevent(kq, changelist.memory(), changelist.size(),
                    MemorySegment.NULL, 0, timeout, capturedState);
            assertEquals(0, ErrnoState.unpackResult(result));

            changelist.clear();
            changelist.add(1, KqueueIO.EVFILT_USER, (short) 0, KqueueIO.NOTE_TRIGGER, 0L, 0L);

            result = KqueueIO.kevent(kq, changelist.memory(), changelist.size(),
                    MemorySegment.NULL, 0, timeout, capturedState);
            assertEquals(0, ErrnoState.unpackResult(result));

            result = KqueueIO.kevent(kq, MemorySegment.NULL, 0,
                    eventlist.memory(), eventlist.capacity(), timeout, capturedState);
            assertEquals(1, ErrnoState.unpackResult(result));
            assertEquals(1, eventlist.ident(0));
            assertEquals(KqueueIO.EVFILT_USER, eventlist.filter(0));
        } finally {
            FileIO.close(kq);
        }
    }
}
