package io.netty.ffm.macos.channel;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;
import io.netty.ffm.macos.NativeSocket;

import java.lang.foreign.Arena;
import java.util.Map;

/**
 * {@link io.netty.channel.ChannelConfig} for {@link KQueueFfmSocketChannel}. Maps standard
 * Netty socket options to FFM {@code setsockopt} calls on the underlying
 * {@link NativeSocket}.
 *
 * <p>Supported options:
 * <ul>
 *   <li>{@link ChannelOption#TCP_NODELAY}</li>
 *   <li>{@link ChannelOption#SO_KEEPALIVE}</li>
 *   <li>{@link ChannelOption#SO_REUSEADDR}</li>
 *   <li>{@link ChannelOption#SO_SNDBUF}</li>
 *   <li>{@link ChannelOption#SO_RCVBUF}</li>
 *   <li>{@link ChannelOption#ALLOW_HALF_CLOSURE}</li>
 * </ul>
 */
public final class KQueueFfmSocketChannelConfig extends DefaultChannelConfig {

    private final NativeSocket socket;
    private volatile boolean allowHalfClosure;

    KQueueFfmSocketChannelConfig(final Channel channel, final NativeSocket socket) {
        super(channel);
        this.socket = socket;
    }

    @Override
    public Map<ChannelOption<?>, Object> getOptions() {
        return getOptions(
                super.getOptions(),
                ChannelOption.TCP_NODELAY,
                ChannelOption.SO_KEEPALIVE,
                ChannelOption.SO_REUSEADDR,
                ChannelOption.SO_SNDBUF,
                ChannelOption.SO_RCVBUF,
                ChannelOption.ALLOW_HALF_CLOSURE);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getOption(final ChannelOption<T> option) {
        if (option == ChannelOption.TCP_NODELAY) {
            return (T) Boolean.valueOf(isTcpNoDelay());
        }
        if (option == ChannelOption.SO_KEEPALIVE) {
            return (T) Boolean.valueOf(isKeepAlive());
        }
        if (option == ChannelOption.SO_REUSEADDR) {
            return (T) Boolean.valueOf(isReuseAddress());
        }
        if (option == ChannelOption.SO_SNDBUF) {
            return (T) Integer.valueOf(getSendBufferSize());
        }
        if (option == ChannelOption.SO_RCVBUF) {
            return (T) Integer.valueOf(getReceiveBufferSize());
        }
        if (option == ChannelOption.ALLOW_HALF_CLOSURE) {
            return (T) Boolean.valueOf(isAllowHalfClosure());
        }
        return super.getOption(option);
    }

    @Override
    public <T> boolean setOption(final ChannelOption<T> option, final T value) {
        validate(option, value);
        if (option == ChannelOption.TCP_NODELAY) {
            setTcpNoDelay((Boolean) value);
        } else if (option == ChannelOption.SO_KEEPALIVE) {
            setKeepAlive((Boolean) value);
        } else if (option == ChannelOption.SO_REUSEADDR) {
            setReuseAddress((Boolean) value);
        } else if (option == ChannelOption.SO_SNDBUF) {
            setSendBufferSize((Integer) value);
        } else if (option == ChannelOption.SO_RCVBUF) {
            setReceiveBufferSize((Integer) value);
        } else if (option == ChannelOption.ALLOW_HALF_CLOSURE) {
            setAllowHalfClosure((Boolean) value);
        } else {
            return super.setOption(option, value);
        }
        return true;
    }

    /**
     * Returns whether TCP_NODELAY (Nagle's algorithm disabled) is set.
     *
     * @return {@code true} if TCP_NODELAY is enabled
     */
    public boolean isTcpNoDelay() {
        try (final Arena arena = Arena.ofConfined()) {
            return socket.getTcpNoDelay(arena) != 0;
        }
    }

    /**
     * Sets TCP_NODELAY (disables Nagle's algorithm for low-latency sends).
     *
     * @param tcpNoDelay {@code true} to disable Nagle's algorithm
     */
    public void setTcpNoDelay(final boolean tcpNoDelay) {
        try (final Arena arena = Arena.ofConfined()) {
            socket.setTcpNoDelay(arena, tcpNoDelay);
        }
    }

    /**
     * Returns whether SO_KEEPALIVE is enabled.
     *
     * @return {@code true} if keepalive probes are sent
     */
    public boolean isKeepAlive() {
        try (final Arena arena = Arena.ofConfined()) {
            return socket.getKeepAlive(arena) != 0;
        }
    }

    /**
     * Sets SO_KEEPALIVE.
     *
     * @param keepAlive {@code true} to enable keepalive probes
     */
    public void setKeepAlive(final boolean keepAlive) {
        try (final Arena arena = Arena.ofConfined()) {
            socket.setKeepAlive(arena, keepAlive);
        }
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
     * Returns the SO_SNDBUF size in bytes.
     *
     * @return the send buffer size
     */
    public int getSendBufferSize() {
        try (final Arena arena = Arena.ofConfined()) {
            return socket.getSendBufferSize(arena);
        }
    }

    /**
     * Sets SO_SNDBUF size in bytes.
     *
     * @param sendBufferSize the desired send buffer size
     */
    public void setSendBufferSize(final int sendBufferSize) {
        try (final Arena arena = Arena.ofConfined()) {
            socket.setSendBufferSize(arena, sendBufferSize);
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
     * Returns whether half-closure is allowed (shutdown input while keeping
     * output open).
     *
     * @return {@code true} if half-closure is enabled
     */
    public boolean isAllowHalfClosure() {
        return allowHalfClosure;
    }

    /**
     * Sets whether half-closure is allowed.
     *
     * @param allowHalfClosure {@code true} to enable half-closure semantics
     */
    public void setAllowHalfClosure(final boolean allowHalfClosure) {
        this.allowHalfClosure = allowHalfClosure;
    }
}
