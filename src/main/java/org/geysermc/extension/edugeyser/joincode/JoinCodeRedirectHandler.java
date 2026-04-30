package org.geysermc.extension.edugeyser.joincode;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.AuthoritativeMovementMode;
import org.cloudburstmc.protocol.bedrock.data.ChatRestrictionLevel;
import org.cloudburstmc.protocol.bedrock.data.EduSharedUriResource;
import org.cloudburstmc.protocol.bedrock.data.GamePublishSetting;
import org.cloudburstmc.protocol.bedrock.data.GameRuleData;
import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission;
import org.cloudburstmc.protocol.bedrock.data.SpawnBiomeType;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.ClientCacheStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.TransferPacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.protocol.common.util.OptionalBoolean;
import org.geysermc.geyser.api.extension.ExtensionLogger;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.UUID;

/**
 * Minimal Bedrock handshake handler that echoes the education server token
 * and sends a TransferPacket to redirect education clients from the Nethernet
 * join code connection to Geyser's RakNet port.
 */
public class JoinCodeRedirectHandler implements BedrockPacketHandler {

    private final BedrockServerSession session;
    private final BedrockCodec codec;
    private final String transferAddress;
    private final int transferPort;
    private final ExtensionLogger logger;
    private boolean networkSettingsRequested = false;

    public JoinCodeRedirectHandler(BedrockServerSession session, BedrockCodec codec,
                                    String transferAddress, int transferPort, ExtensionLogger logger) {
        this.session = session;
        this.codec = codec;
        this.transferAddress = transferAddress;
        this.transferPort = transferPort;
        this.logger = logger;
    }

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        BedrockPacketHandler.super.handlePacket(packet);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        session.setCodec(codec);

        NetworkSettingsPacket settings = new NetworkSettingsPacket();
        settings.setCompressionThreshold(0);
        settings.setCompressionAlgorithm(PacketCompressionAlgorithm.ZLIB);
        session.sendPacketImmediately(settings);
        session.setCompression(PacketCompressionAlgorithm.ZLIB);

        networkSettingsRequested = true;
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(LoginPacket packet) {
        if (!networkSettingsRequested) {
            PlayStatusPacket status = new PlayStatusPacket();
            status.setStatus(PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD);
            session.sendPacket(status);
            session.disconnect();
            return PacketSignal.HANDLED;
        }

        // Extract the server token from the client's EduTokenChain for echoing
        String serverToken = null;
        try {
            String clientJwt = packet.getClientJwt();
            if (clientJwt != null) {
                String[] jwtParts = clientJwt.split("\\.");
                if (jwtParts.length >= 2) {
                    String padded = jwtParts[1];
                    while (padded.length() % 4 != 0) padded += "=";
                    String json = new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
                    JsonObject clientData = JsonParser.parseString(json).getAsJsonObject();
                    if (clientData.has("EduTokenChain") && !clientData.get("EduTokenChain").getAsString().isEmpty()) {
                        serverToken = extractServerToken(clientData.get("EduTokenChain").getAsString());
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("[JoinCode] Failed to parse EduTokenChain: " + e.getMessage());
        }

        // Send the handshake with the echoed server token
        try {
            sendEducationHandshake(serverToken);
        } catch (Exception e) {
            logger.error("[JoinCode] Failed to send handshake: " + e.getMessage());
            session.disconnect();
        }

        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ClientToServerHandshakePacket packet) {
        // Client accepted the handshake. Continue with login success.
        PlayStatusPacket status = new PlayStatusPacket();
        status.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
        session.sendPacket(status);

        ResourcePacksInfoPacket info = new ResourcePacksInfoPacket();
        info.setWorldTemplateId(UUID.randomUUID());
        info.setWorldTemplateVersion("*");
        info.setForcedToAccept(false);
        session.sendPacket(info);

        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ClientCacheStatusPacket packet) {
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePackClientResponsePacket packet) {
        switch (packet.getStatus()) {
            case COMPLETED -> sendStartGameAndTransfer();
            case HAVE_ALL_PACKS -> {
                ResourcePackStackPacket stack = new ResourcePackStackPacket();
                stack.setExperimentsPreviouslyToggled(false);
                stack.setForcedToAccept(false);
                stack.setGameVersion("*");
                session.sendPacket(stack);
            }
            default -> session.disconnect("disconnectionScreen.resourcePack");
        }
        return PacketSignal.HANDLED;
    }

    private void sendEducationHandshake(String serverToken) throws Exception {
        KeyPair serverKeyPair = EncryptionUtils.createKeyPair();
        byte[] token = EncryptionUtils.generateRandomToken();

        JsonWebSignature jws = new JsonWebSignature();
        jws.setAlgorithmHeaderValue("ES384");
        jws.setHeader("x5u", Base64.getEncoder().encodeToString(
                serverKeyPair.getPublic().getEncoded()));
        jws.setKey(serverKeyPair.getPrivate());

        JwtClaims claims = new JwtClaims();
        claims.setClaim("salt", Base64.getEncoder().encodeToString(token));
        if (serverToken != null) {
            claims.setClaim("signedToken", serverToken);
        }
        jws.setPayload(claims.toJson());

        ServerToClientHandshakePacket handshake = new ServerToClientHandshakePacket();
        handshake.setJwt(jws.getCompactSerialization());
        session.sendPacketImmediately(handshake);

        // Nethernet peer has encryption as no-op, but we still call it for protocol correctness
        // The client will process the JWT for token verification, not for actual encryption
    }

    private String extractServerToken(String eduTokenChain) {
        try {
            String[] jwtParts = eduTokenChain.split("\\.");
            if (jwtParts.length < 2) return null;
            String padded = jwtParts[1];
            while (padded.length() % 4 != 0) padded += "=";
            String json = new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
            JsonObject payload = JsonParser.parseString(json).getAsJsonObject();
            if (!payload.has("chain")) return null;
            String chain = payload.get("chain").getAsString();
            String[] parts = chain.split("\\|");
            return parts.length >= 4 ? chain : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private void sendStartGameAndTransfer() {
        StartGamePacket startGame = new StartGamePacket();
        startGame.setUniqueEntityId(1);
        startGame.setRuntimeEntityId(1);
        startGame.setPlayerGameType(GameType.CREATIVE);
        startGame.setPlayerPosition(Vector3f.from(0, 66, 0));
        startGame.setRotation(Vector2f.ONE);
        startGame.setPlayerPropertyData(NbtMap.EMPTY);
        startGame.setSeed(0L);
        startGame.setDimensionId(2);
        startGame.setGeneratorId(1);
        startGame.setSpawnBiomeType(SpawnBiomeType.DEFAULT);
        startGame.setCustomBiomeName("");
        startGame.setForceExperimentalGameplay(OptionalBoolean.empty());
        startGame.setLevelGameType(GameType.CREATIVE);
        startGame.setDifficulty(0);
        startGame.setDefaultSpawn(Vector3i.ZERO);
        startGame.setAchievementsDisabled(true);
        startGame.setCurrentTick(-1);
        startGame.setEduEditionOffers(0);
        startGame.setEduFeaturesEnabled(false);
        startGame.setEducationProductionId("");
        startGame.setEduSharedUriResource(EduSharedUriResource.EMPTY);
        startGame.setRainLevel(0);
        startGame.setLightningLevel(0);
        startGame.setMultiplayerGame(true);
        startGame.setBroadcastingToLan(true);
        startGame.getGamerules().add(new GameRuleData<>("showcoordinates", false));
        startGame.setPlatformBroadcastMode(GamePublishSetting.PUBLIC);
        startGame.setXblBroadcastMode(GamePublishSetting.PUBLIC);
        startGame.setCommandsEnabled(true);
        startGame.setChatRestrictionLevel(ChatRestrictionLevel.NONE);
        startGame.setTexturePacksRequired(false);
        startGame.setBonusChestEnabled(false);
        startGame.setStartingWithMap(false);
        startGame.setTrustingPlayers(true);
        startGame.setDefaultPlayerPermission(PlayerPermission.VISITOR);
        startGame.setServerChunkTickRange(4);
        startGame.setBehaviorPackLocked(false);
        startGame.setResourcePackLocked(false);
        startGame.setFromLockedWorldTemplate(false);
        startGame.setUsingMsaGamertagsOnly(false);
        startGame.setFromWorldTemplate(false);
        startGame.setWorldTemplateOptionLocked(false);
        startGame.setServerEngine("");
        startGame.setLevelId("");
        startGame.setLevelName("EduGeyser");
        startGame.setPremiumWorldTemplateId("");
        startGame.setWorldTemplateId(new UUID(0, 0));
        startGame.setCurrentTick(0);
        startGame.setEnchantmentSeed(0);
        startGame.setMultiplayerCorrelationId("");
        startGame.setVanillaVersion("*");
        startGame.setAuthoritativeMovementMode(AuthoritativeMovementMode.SERVER);
        startGame.setRewindHistorySize(0);
        startGame.setServerAuthoritativeBlockBreaking(false);
        startGame.setServerId("");
        startGame.setWorldId("");
        startGame.setScenarioId("");
        startGame.setOwnerId("");

        session.sendPacket(startGame);

        TransferPacket transfer = new TransferPacket();
        transfer.setAddress(transferAddress);
        transfer.setPort(transferPort);
        session.sendPacket(transfer);

        logger.debug("[JoinCode] Redirected education client to " + transferAddress + ":" + transferPort);
    }
}
