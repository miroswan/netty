package io.netty.ffm.macos.channel;

import io.netty.channel.DefaultFileRegion;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;

/**
 * Utility for extracting native file descriptor integers from Java NIO types and
 * updating {@link DefaultFileRegion}'s internal transfer counter. Uses
 * {@link VarHandle} for reflective field access without Unsafe.
 *
 * <p>This bridges the gap between Netty's file region abstraction and FFM's sendfile
 * wrapper, which needs raw fd integers and must update the transferred byte count
 * after partial sends.
 */
final class FileDescriptorUtil {

    private static final VarHandle FILE_CHANNEL_HANDLE;
    private static final VarHandle TRANSFERRED_HANDLE;
    private static final VarHandle FD_HANDLE;
    private static final VarHandle FD_INT_HANDLE;

    static {
        try {
            final MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    DefaultFileRegion.class, MethodHandles.lookup());
            FILE_CHANNEL_HANDLE = lookup.findVarHandle(DefaultFileRegion.class, "file", FileChannel.class);
            TRANSFERRED_HANDLE = lookup.findVarHandle(DefaultFileRegion.class, "transferred", long.class);

            final Class<?> fileChannelImpl = Class.forName("sun.nio.ch.FileChannelImpl");
            final MethodHandles.Lookup fcLookup = MethodHandles.privateLookupIn(
                    fileChannelImpl, MethodHandles.lookup());
            FD_HANDLE = fcLookup.findVarHandle(fileChannelImpl, "fd", java.io.FileDescriptor.class);

            final MethodHandles.Lookup fdLookup = MethodHandles.privateLookupIn(
                    java.io.FileDescriptor.class, MethodHandles.lookup());
            FD_INT_HANDLE = fdLookup.findVarHandle(java.io.FileDescriptor.class, "fd", int.class);
        } catch (final Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private FileDescriptorUtil() {
    }

    /**
     * Extracts the native file descriptor integer from a {@link DefaultFileRegion}.
     * Navigates: DefaultFileRegion.file → FileChannelImpl.fd → FileDescriptor.fd
     *
     * @param region the file region to extract the fd from
     * @return the native file descriptor integer
     * @throws IllegalStateException if the region's file channel is null (not opened)
     */
    static int getFd(final DefaultFileRegion region) {
        final FileChannel fileChannel = (FileChannel) FILE_CHANNEL_HANDLE.get(region);
        if (fileChannel == null) {
            throw new IllegalStateException("DefaultFileRegion file channel is null (not opened?)");
        }
        final java.io.FileDescriptor fd = (java.io.FileDescriptor) FD_HANDLE.get(fileChannel);
        return (int) FD_INT_HANDLE.get(fd);
    }

    /**
     * Updates the transferred byte count on a {@link DefaultFileRegion}. Called after
     * a successful (possibly partial) sendfile to advance the region's internal counter.
     *
     * @param region the file region to update
     * @param newTransferred the new total transferred byte count
     */
    static void setTransferred(final DefaultFileRegion region, final long newTransferred) {
        TRANSFERRED_HANDLE.set(region, newTransferred);
    }
}
