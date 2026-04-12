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

/**
 * Lightweight Nethernet (WebRTC) server that accepts incoming connections from
 * education clients using join codes and redirects them to Geyser's RakNet port
 * via TransferPacket.
 *
 * Based on MCXboxBroadcast's Nethernet micro-server pattern.
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
        this.signaling = new NetherNetXboxSignaling(netherNetId, mcTokenHeader);

        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channelFactory(NetherNetChannelFactory.server(new PeerConnectionFactory(), signaling))
                    .childHandler(new JoinCodeChannelInitializer(codec, transferAddress, transferPort, logger));

            this.netherNetChannel = bootstrap.bind(new InetSocketAddress(0)).sync().channel();

            logger.info("[JoinCode] Nethernet server started on ID: " + netherNetId);
            return netherNetId;
        } catch (Exception e) {
            logger.error("[JoinCode] Failed to start Nethernet server: " + e.getMessage());
            shutdown();
            return -1;
        }
    }

    /**
     * Shuts down the Nethernet server and cleans up resources.
     */
    public void shutdown() {
        if (netherNetChannel != null) {
            netherNetChannel.close().syncUninterruptibly();
            netherNetChannel = null;
        }
        if (signaling != null) {
            signaling.close();
            signaling = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
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
