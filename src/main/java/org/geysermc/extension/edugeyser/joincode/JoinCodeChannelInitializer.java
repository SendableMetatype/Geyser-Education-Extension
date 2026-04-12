package org.geysermc.extension.edugeyser.joincode;

import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.geysermc.geyser.api.extension.ExtensionLogger;

/**
 * Channel initializer for incoming Nethernet connections from join code clients.
 * Sets up the redirect handler that transfers clients to Geyser's RakNet port.
 */
public class JoinCodeChannelInitializer extends NetherNetBedrockChannelInitializer<BedrockServerSession> {

    private final BedrockCodec codec;
    private final String transferAddress;
    private final int transferPort;
    private final ExtensionLogger logger;

    public JoinCodeChannelInitializer(BedrockCodec codec, String transferAddress, int transferPort, ExtensionLogger logger) {
        this.codec = codec;
        this.transferAddress = transferAddress;
        this.transferPort = transferPort;
        this.logger = logger;
    }

    @Override
    protected BedrockServerSession createSession0(BedrockPeer peer, int subClientId) {
        return new BedrockServerSession(peer, subClientId);
    }

    @Override
    protected void initSession(BedrockServerSession session) {
        session.setPacketHandler(new JoinCodeRedirectHandler(session, codec, transferAddress, transferPort, logger));
    }
}
