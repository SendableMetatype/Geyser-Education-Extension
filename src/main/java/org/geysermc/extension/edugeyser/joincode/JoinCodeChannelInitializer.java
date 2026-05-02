package org.geysermc.extension.edugeyser.joincode;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import org.geysermc.geyser.api.extension.ExtensionLogger;

/**
 * Channel initializer for incoming Nethernet connections from join code clients.
 * Sets up a transparent byte relay to the proxy's RakNet listener.
 *
 * No Bedrock protocol codecs are added - the relay forwards raw bytes between
 * the Nethernet data channel and a RakNet client connection, only adding or
 * stripping the 0xFE game packet prefix.
 */
public class JoinCodeChannelInitializer extends ChannelInitializer<Channel> {

    private final String geyserHost;
    private final int geyserPort;
    private final ExtensionLogger logger;

    public JoinCodeChannelInitializer(String geyserHost, int geyserPort, ExtensionLogger logger) {
        this.geyserHost = geyserHost;
        this.geyserPort = geyserPort;
        this.logger = logger;
    }

    @Override
    protected void initChannel(Channel channel) {
        channel.pipeline().addLast("relay", new NetherNetRelayHandler(geyserHost, geyserPort, logger));
    }
}
