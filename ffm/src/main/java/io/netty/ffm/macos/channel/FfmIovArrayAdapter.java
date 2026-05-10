package io.netty.ffm.macos.channel;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.ffm.posix.IovArray;

import java.lang.foreign.MemorySegment;

/**
 * Bridges Netty's {@link ChannelOutboundBuffer} to the FFM {@link IovArray} for vectored
 * writes. Implements {@link ChannelOutboundBuffer.MessageProcessor} so it can be passed to
 * {@link ChannelOutboundBuffer#forEachFlushedMessage(ChannelOutboundBuffer.MessageProcessor)}.
 *
 * <p>For each flushed {@link ByteBuf}, extracts its direct memory address and length,
 * wraps it as a {@link MemorySegment}, and adds it to the IovArray. Stops when the
 * IovArray is full or the byte limit is reached.
 *
 * <p>Only direct ByteBufs with a memory address are supported. Heap buffers should have
 * been converted to direct via {@code filterOutboundMessage} before reaching this point.
 */
public final class FfmIovArrayAdapter implements ChannelOutboundBuffer.MessageProcessor {

    private IovArray iovArray;
    private long maxBytes;
    private long accumulatedBytes;

    /**
     * Configures this adapter to populate the given IovArray up to the specified byte limit.
     * Call this before passing the adapter to {@code forEachFlushedMessage}.
     *
     * @param iovArray the target iov array (should be freshly cleared)
     * @param maxBytes the maximum total bytes to accumulate across all entries
     * @return this adapter for convenience
     */
    public FfmIovArrayAdapter prepare(final IovArray iovArray, final long maxBytes) {
        this.iovArray = iovArray;
        this.maxBytes = maxBytes;
        this.accumulatedBytes = 0;
        return this;
    }

    /**
     * Processes a single flushed message from the outbound buffer. If the message is a
     * {@link ByteBuf} with readable data and a memory address, adds it to the IovArray.
     * Returns {@code false} to stop iteration when the array is full or byte limit is reached.
     *
     * @param msg the flushed message (expected to be a direct ByteBuf)
     * @return {@code true} to continue processing, {@code false} to stop
     * @throws Exception if an unexpected message type is encountered
     */
    @Override
    public boolean processMessage(final Object msg) throws Exception {
        if (msg instanceof ByteBuf buf) {
            final int readableBytes = buf.readableBytes();
            if (readableBytes == 0) {
                return true;
            }
            if (!buf.hasMemoryAddress()) {
                return false;
            }
            if (iovArray.isFull()) {
                return false;
            }
            if (accumulatedBytes + readableBytes > maxBytes) {
                return false;
            }
            final long addr = buf.memoryAddress() + buf.readerIndex();
            final MemorySegment seg = MemorySegment.ofAddress(addr).reinterpret(readableBytes);
            iovArray.add(seg, readableBytes);
            accumulatedBytes += readableBytes;
            return true;
        }
        return false;
    }
}
