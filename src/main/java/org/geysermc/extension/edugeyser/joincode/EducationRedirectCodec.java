package org.geysermc.extension.edugeyser.joincode;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v898.serializer.StartGameSerializer_v898;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

/**
 * Wraps a base codec to use the Education Edition StartGamePacket serializer,
 * which appends 3 extra strings after ownerId in level settings.
 * Without these, the education client desyncs reading the packet.
 */
public final class EducationRedirectCodec {

    private EducationRedirectCodec() {
    }

    public static BedrockCodec wrap(BedrockCodec baseCodec) {
        return baseCodec.toBuilder()
                .updateSerializer(StartGamePacket.class, EducationStartGameSerializer.INSTANCE)
                .build();
    }

    private static class EducationStartGameSerializer extends StartGameSerializer_v898 {
        static final EducationStartGameSerializer INSTANCE = new EducationStartGameSerializer();

        @Override
        protected void writeLevelSettings(ByteBuf buffer, BedrockCodecHelper helper, StartGamePacket packet) {
            super.writeLevelSettings(buffer, helper, packet);
            helper.writeString(buffer, ""); // educationReferrerId
            helper.writeString(buffer, ""); // educationCreatorWorldId
            helper.writeString(buffer, ""); // educationCreatorId
        }

        @Override
        protected void readLevelSettings(ByteBuf buffer, BedrockCodecHelper helper, StartGamePacket packet) {
            super.readLevelSettings(buffer, helper, packet);
            helper.readString(buffer); // educationReferrerId
            helper.readString(buffer); // educationCreatorWorldId
            helper.readString(buffer); // educationCreatorId
        }
    }
}
