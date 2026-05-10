package io.netty.ffm.macos.channel;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;
import io.netty.ffm.macos.NativeSocket;

import java.lang.foreign.Arena;
import java.util.Map;

/**
 * {@link io.netty.channel.ChannelConfig} for {@link KQueueFfmServerSocketChannel}. Maps
 * server-relevant socket options to FFM {@code setsockopt} calls.
 *
 * <p>Supported options:
 * <ul>
 *   <li>{@link ChannelOption#SO_REUSEADDR}</li>
 *   <li>{@link ChannelOption#SO_RCVBUF}</li>
 *   <li>{@link ChannelOption#SO_BACKLOG}</li>
 * </ul>
 */
public final class KQueueFfmServerSocketChannelConfig extends DefaultChannelConfig {

    private final NativeSocket socket;
    private volatile int backlog = 128;

    KQueueFfmServerSocketChannelConfig(final Channel channel, final NativeSocket socket) {
        super(channel);
        this.socket = socket;
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return getOptions(
                super.getOptions(),
                ChannelOption.SO_REUSEADDR,
                ChannelOption.SO_RCVBUF,
                ChannelOption.SO_BACKLOG);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(final ChannelOption<T> option) {
        if (option == ChannelOption.SO_REUSEADDR) {
            return (T) Boolean.valueOf(isReuseAddress());
        }
        if (option == ChannelOption.SO_RCVBUF) {
            return (T) Integer.valueOf(getReceiveBufferSize());
        }
        if (option == ChannelOption.SO_BACKLOG) {
            return (T) Integer.valueOf(getBacklog());
        }
        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(final ChannelOption<T> option, final T value) {
        validate(option, value);
        if (option == ChannelOption.SO_REUSEADDR) {
            setReuseAddress((Boolean) value);
        } else if (option == ChannelOption.SO_RCVBUF) {
            setReceiveBufferSize((Integer) value);
        } else if (option == ChannelOption.SO_BACKLOG) {
            setBacklog((Integer) value);
        } else {
            return super.setOption(option, value);
        }
        return true;
    }

    /**
     * Returns whether SO_REUSEADDR is enabled.
     *
     * @return {@code true} if address reuse is allowed
     */
    public boolean isReuseAddress() {
        try (final Arena arena = Arena.ofConfined()) {
            return socket.getReuseAddress(arena) != 0;
        }
    }

    /**
     * Sets SO_REUSEADDR.
     *
     * @param reuseAddress {@code true} to allow address reuse
     */
    public void setReuseAddress(final boolean reuseAddress) {
        try (final Arena arena = Arena.ofConfined()) {
            socket.setReuseAddress(arena, reuseAddress);
        }
    }

    /**
     * Returns the SO_RCVBUF size in bytes.
     *
     * @return the receive buffer size
     */
    public int getReceiveBufferSize() {
        try (final Arena arena = Arena.ofConfined()) {
            return socket.getReceiveBufferSize(arena);
        }
    }

    /**
     * Sets SO_RCVBUF size in bytes.
     *
     * @param receiveBufferSize the desired receive buffer size
     */
    public void setReceiveBufferSize(final int receiveBufferSize) {
        try (final Arena arena = Arena.ofConfined()) {
            socket.setReceiveBufferSize(arena, receiveBufferSize);
        }
    }

    /**
     * Returns the listen backlog (maximum pending connection queue length).
     *
     * @return the backlog value
     */
    public int getBacklog() {
        return backlog;
    }

    /**
     * Sets the listen backlog. Must be set before {@code bind()} for it to take effect.
     *
     * @param backlog the desired backlog
     */
    public void setBacklog(final int backlog) {
        this.backlog = backlog;
    }
}
