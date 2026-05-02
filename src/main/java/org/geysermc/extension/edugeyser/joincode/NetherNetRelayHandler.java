package org.geysermc.extension.edugeyser.joincode;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.cloudburstmc.protocol.common.util.Zlib;
import org.geysermc.geyser.api.extension.ExtensionLogger;
import org.geysermc.geyser.network.netty.BedrockEncryptionControl;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Bridges a Nethernet (WebRTC) channel to the proxy's RakNet listener.
 *
 * Nethernet delivers ByteBufs without the 0xFE game packet prefix.
 * RakNet expects ByteBufs and delivers RakMessages with the 0xFE prefix.
 * This handler adds 0xFE on the way in (Nethernet -> RakNet) and strips
 * it on the way out (RakNet -> Nethernet). The packet payload - including
 * compression - passes through untouched.
 */
public class NetherNetRelayHandler extends ChannelInboundHandlerAdapter {

    private static final byte GAME_PACKET_ID = (byte) 0xFE;
    private static final int DISCONNECT_PACKET_ID = 5;
    private static final int MAX_DISCONNECT_SCAN_BYTES = 1024 * 1024;

    private final String geyserHost;
    private final int geyserPort;
    private final ExtensionLogger logger;

    private Channel netherNetChannel;
    private Channel rakNetChannel;
    private final Queue<ByteBuf> pendingFromClient = new ArrayDeque<>();
    private boolean rakNetReady = false;
    private boolean rakNetDisconnecting = false;

    public NetherNetRelayHandler(String geyserHost, int geyserPort, ExtensionLogger logger) {
        this.geyserHost = geyserHost;
        this.geyserPort = geyserPort;
        this.logger = logger;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.netherNetChannel = ctx.channel();
        ctx.channel().closeFuture().addListener(future -> {
            disconnectRakNet();
            drainPending();
        });
        connectToGeyser(ctx);
        super.channelActive(ctx);
    }

    /**
     * Called when the Nethernet channel receives a message from the Education client.
     * Prepends 0xFE and forwards to the proxy's RakNet listener.
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf clientBuf)) {
            ReferenceCountUtil.release(msg);
            return;
        }

        if (containsDisconnectPacket(clientBuf)) {
            clientBuf.release();
            disconnectRakNet();
            ctx.close();
            return;
        }

        if (!rakNetReady) {
            // RakNet connection still handshaking. Buffer the message.
            pendingFromClient.add(clientBuf);
            return;
        }

        forwardToRakNet(clientBuf);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        disconnectRakNet();
        drainPending();
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.debug("[Relay] Nethernet channel error: " + cause.getMessage());
        ctx.close();
        disconnectRakNet();
    }

    private void connectToGeyser(ChannelHandlerContext netherNetCtx) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(netherNetCtx.channel().eventLoop())
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                .option(RakChannelOption.RAK_MTU, 1400)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast("relay-server", new RakNetInboundHandler());
                    }
                });

        bootstrap.connect(new InetSocketAddress(geyserHost, geyserPort))
                .addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        rakNetChannel = future.channel();
                        BedrockEncryptionControl.sendDisableEncryptionRequest(rakNetChannel).addListener(markerFuture -> {
                            if (!markerFuture.isSuccess()) {
                                logger.error("[Relay] Failed to request no-encryption Bedrock session: "
                                        + markerFuture.cause().getMessage());
                                disconnectRakNet();
                                netherNetCtx.close();
                                return;
                            }

                            rakNetReady = true;
                            flushPending();
                            logger.debug("[Relay] RakNet connection to " + geyserHost + ":" + geyserPort
                                    + " established");
                        });
                    } else {
                        logger.error("[Relay] Failed to connect to the proxy RakNet listener on "
                                + geyserHost + ":" + geyserPort + " - " + future.cause().getMessage());
                        netherNetCtx.close();
                    }
                });
    }

    private void disconnectRakNet() {
        Channel channel = rakNetChannel;
        if (channel == null || rakNetDisconnecting) {
            return;
        }
        rakNetDisconnecting = true;

        if (channel instanceof RakChannel rakChannel) {
            RakSessionCodec sessionCodec = rakChannel.rakPipeline().get(RakSessionCodec.class);
            if (sessionCodec != null && !sessionCodec.isClosed()) {
                sessionCodec.disconnect(RakDisconnectReason.DISCONNECTED);
                return;
            }
        }

        if (channel.isActive() || channel.isOpen()) {
            channel.disconnect();
        } else {
            channel.close();
        }
    }

    private boolean containsDisconnectPacket(ByteBuf buf) {
        if (containsDisconnectPacketInBatch(buf.duplicate())) {
            return true;
        }

        if (!buf.isReadable()) {
            return false;
        }

        ByteBuf compressed = buf.duplicate();
        int compressionHeader = compressed.readUnsignedByte();
        if (compressionHeader == 0xff) {
            return containsDisconnectPacketInBatch(compressed);
        }

        if (compressionHeader != 0x00) {
            return false;
        }

        ByteBuf decompressed = null;
        try {
            decompressed = Zlib.RAW.inflate(compressed, MAX_DISCONNECT_SCAN_BYTES);
            return containsDisconnectPacketInBatch(decompressed);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (decompressed != null) {
                decompressed.release();
            }
        }
    }

    private boolean containsDisconnectPacketInBatch(ByteBuf batch) {
        try {
            while (batch.isReadable()) {
                int packetLength = readUnsignedVarInt(batch);
                if (packetLength < 1 || packetLength > batch.readableBytes()) {
                    return false;
                }

                ByteBuf packet = batch.readSlice(packetLength);
                int packetHeader = readUnsignedVarInt(packet);
                if ((packetHeader & 0x3ff) == DISCONNECT_PACKET_ID) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private int readUnsignedVarInt(ByteBuf buf) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 7) {
            if (!buf.isReadable()) {
                throw new IllegalArgumentException("Incomplete VarInt");
            }

            int value = buf.readUnsignedByte();
            result |= (value & 0x7f) << shift;
            if ((value & 0x80) == 0) {
                return result;
            }
        }

        throw new IllegalArgumentException("VarInt is too large");
    }

    /**
     * Prepends 0xFE to a Nethernet ByteBuf and writes it to the RakNet channel.
     */
    private void forwardToRakNet(ByteBuf clientBuf) {
        ByteBuf framed = rakNetChannel.alloc().buffer(1 + clientBuf.readableBytes());
        framed.writeByte(GAME_PACKET_ID);
        framed.writeBytes(clientBuf);
        clientBuf.release();
        rakNetChannel.writeAndFlush(framed);
    }

    /**
     * Flushes any messages buffered while the RakNet connection was being established.
     */
    private void flushPending() {
        ByteBuf buf;
        while ((buf = pendingFromClient.poll()) != null) {
            forwardToRakNet(buf);
        }
    }

    /**
     * Releases any unreleased buffered messages on shutdown.
     */
    private void drainPending() {
        ByteBuf buf;
        while ((buf = pendingFromClient.poll()) != null) {
            buf.release();
        }
    }

    /**
     * Handler on the RakNet client channel. Receives RakMessages from Geyser
     * (with 0xFE prefix), strips the prefix, and forwards to the Nethernet channel.
     */
    private class RakNetInboundHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof RakMessage rakMessage) {
                try {
                    forwardServerBufferToNetherNet(rakMessage.content());
                } finally {
                    rakMessage.release();
                }
                return;
            }

            if (msg instanceof ByteBuf serverBuf) {
                try {
                    forwardServerBufferToNetherNet(serverBuf);
                } finally {
                    serverBuf.release();
                }
                return;
            }

            ReferenceCountUtil.release(msg);
        }

        private void forwardServerBufferToNetherNet(ByteBuf serverBuf) {
            if (serverBuf.readableBytes() < 1) {
                return;
            }

            byte frameId = serverBuf.readByte();
            if (frameId != GAME_PACKET_ID) {
                // Not a game packet. Discard - internal RakNet message that
                // leaked into the pipeline (shouldn't happen, but be safe).
                return;
            }

            // Forward the payload (without 0xFE) to the Nethernet channel.
            if (netherNetChannel != null && netherNetChannel.isActive()) {
                netherNetChannel.writeAndFlush(serverBuf.retainedSlice());
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (netherNetChannel != null) {
                netherNetChannel.close();
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.debug("[Relay] RakNet channel error: " + cause.getMessage());
            ctx.close();
            if (netherNetChannel != null) {
                netherNetChannel.close();
            }
        }
    }
}
