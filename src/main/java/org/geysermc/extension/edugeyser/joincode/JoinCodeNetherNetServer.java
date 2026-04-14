package org.geysermc.extension.edugeyser.joincode;

import dev.kastle.netty.channel.nethernet.NetherNetChannelFactory;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetXboxSignaling;
import dev.kastle.webrtc.PeerConnectionFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.geysermc.geyser.api.extension.ExtensionLogger;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight Nethernet (WebRTC) server that accepts incoming connections from
 * education clients using join codes and redirects them to Geyser's RakNet port
 * via TransferPacket.
 *
 * The signaling WebSocket is periodically rebuilt via {@link #restartSignaling(String)}
 * to prevent stale/dropped connections from accumulating over long-running sessions.
 * Existing WebRTC peer connections are unaffected since they don't rely on the
 * signaling server after initial negotiation.
 */
public class JoinCodeNetherNetServer {

    private final ExtensionLogger logger;
    private final BedrockCodec codec;
    private final String transferAddress;
    private final int transferPort;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel netherNetChannel;
    private NetherNetXboxSignaling signaling;
    private long netherNetId;

    public JoinCodeNetherNetServer(ExtensionLogger logger, BedrockCodec codec,
                                    String transferAddress, int transferPort) {
        this.logger = logger;
        this.codec = codec;
        this.transferAddress = transferAddress;
        this.transferPort = transferPort;
    }

    /**
     * Starts the Nethernet server with the given MCToken for signaling authentication.
     * @param mcTokenHeader the MCToken authorization header (e.g. "MCToken eyJ...")
     * @return the nethernetID to register with Discovery, or -1 on failure
     */
    public long start(String mcTokenHeader) {
        return start(mcTokenHeader, generateNetherNetId());
    }

    /**
     * Starts the Nethernet server with a specific nethernetId (for session restore).
     * @param mcTokenHeader the MCToken authorization header
     * @param netherNetId the nethernetId to reuse
     * @return the nethernetID, or -1 on failure
     */
    public long start(String mcTokenHeader, long netherNetId) {
        shutdown();
        this.netherNetId = netherNetId;
        if (!bindInternal(mcTokenHeader)) {
            shutdown();
            return -1;
        }
        logger.debug("[JoinCode] Nethernet server started on ID: " + netherNetId);
        return netherNetId;
    }

    /**
     * Rebuilds the signaling WebSocket and Netty channel with a fresh
     * {@link PeerConnectionFactory}, keeping the same nethernetID. Existing
     * WebRTC peer connections are unaffected — they don't go through signaling
     * after initial negotiation.
     *
     * Note: {@link NetherNetServerChannel#doClose()} calls {@code factory.dispose()},
     * so the factory is always owned by the channel and cannot be reused across
     * channel restarts. We create a fresh one each cycle.
     *
     * @param mcTokenHeader a fresh MCToken authorization header
     * @return true if the signaling reconnected successfully
     */
    public synchronized boolean restartSignaling(String mcTokenHeader) {
        closeChannel();
        return bindInternal(mcTokenHeader);
    }

    private boolean bindInternal(String mcTokenHeader) {
        // Create a fresh PeerConnectionFactory — the channel will dispose it on close.
        PeerConnectionFactory factory = new PeerConnectionFactory();
        this.signaling = new NetherNetXboxSignaling(netherNetId, mcTokenHeader);
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup(2);
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channelFactory(NetherNetChannelFactory.server(factory, signaling))
                    .childHandler(new JoinCodeChannelInitializer(codec, transferAddress, transferPort, logger));
            this.netherNetChannel = bootstrap.bind(new InetSocketAddress(0)).sync().channel();
            return true;
        } catch (Exception e) {
            logger.error("[JoinCode] Failed to bind Nethernet server: " + e.getMessage());
            // If bind failed, clean up the factory and event loops we just made
            try { factory.dispose(); } catch (Exception ignored) {}
            this.signaling = null;
            if (bossGroup != null) { bossGroup.shutdownGracefully(); bossGroup = null; }
            if (workerGroup != null) { workerGroup.shutdownGracefully(); workerGroup = null; }
            return false;
        }
    }

    /**
     * Closes the Netty channel, which also disposes the PeerConnectionFactory
     * and closes the signaling WebSocket via NetherNetServerChannel#doClose().
     * Waits for event loop shutdown to complete so the native WebRTC library
     * has time to fully release resources before a rebind creates a new factory.
     */
    private void closeChannel() {
        if (netherNetChannel != null) {
            netherNetChannel.close().syncUninterruptibly();
            netherNetChannel = null;
        }
        signaling = null;
        // Zero quiet period — we're not draining traffic, just shutting down.
        // Wait synchronously so the native WebRTC library has fully released its
        // resources before bindInternal creates a new PeerConnectionFactory.
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            workerGroup = null;
        }
    }

    /**
     * Checks if the upstream signaling WebSocket to Microsoft is still alive.
     * Uses the patched library's public {@code isChannelAlive()} accessor.
     * Only detects cleanly-closed channels — for stricter checking pass a
     * silence threshold.
     */
    public boolean isSignalingAlive() {
        return signaling != null && signaling.isChannelAlive();
    }

    /**
     * Stricter health check: returns true only if the channel is active AND
     * we have received a frame from the signaling server within the given
     * window. Useful for detecting silent half-closed TCP where Netty lies
     * about the channel state.
     */
    public boolean isSignalingAlive(long maxSilenceMillis) {
        return signaling != null && signaling.isChannelAlive(maxSilenceMillis);
    }

    /**
     * @return milliseconds since the last frame received from the signaling
     *         server, or -1 if never connected / no frame yet.
     */
    public long getSignalingSilenceMillis() {
        return signaling != null ? signaling.getMillisSinceLastMessage() : -1;
    }

    /**
     * Shuts down the Nethernet server and cleans up all resources.
     */
    public void shutdown() {
        closeChannel();
    }

    public long getNetherNetId() {
        return netherNetId;
    }

    public boolean isRunning() {
        return netherNetChannel != null && netherNetChannel.isActive();
    }

    /**
     * Generate a random Nethernet ID (uint64, first digit nonzero).
     */
    private static long generateNetherNetId() {
        long id;
        do {
            id = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        } while (id <= 0 || String.valueOf(id).charAt(0) == '0');
        return id;
    }
}
