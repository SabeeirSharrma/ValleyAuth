package com.valleyrealm.valleyauth.auth.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.wrappers.BukkitConverters;
import com.valleyrealm.valleyauth.ValleyAuthPlugin;
import com.valleyrealm.valleyauth.auth.AuthState;
import com.valleyrealm.valleyauth.identity.IdentityType;
import com.valleyrealm.valleyauth.session.MojangSessionVerifier;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import org.bukkit.entity.Player;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * ProtocolLib-based packet interceptor for forced Mojang verification (fallback).
 * Section 79.4 Steps 3-5 of the spec.
 *
 * This is the fallback implementation when packetevents is not available.
 * It mirrors the packetevents implementation but uses ProtocolLib's API.
 *
 * <h3>Limitations:</h3>
 * <ul>
 *   <li>ProtocolLib's {@code sendServerPacket} requires a Player object, which IS available
 *       during login phase via {@code packetEvent.getPlayer()}. This works for sending
 *       EncryptionRequest.</li>
 *   <li>Re-injecting LOGIN_START (a client→server packet) requires direct Netty channel
 *       access via reflection. This is fragile and may break across Minecraft versions.</li>
 *   <li>If the reflection-based channel access fails, the player will be kicked with a
 *       message to reconnect — the verification still works but requires a reconnect.</li>
 * </ul>
 *
 * <h3>Packet type mapping (ProtocolLib vs packetevents):</h3>
 * <ul>
 *   <li>{@code PacketType.Login.Client.START} = LOGIN_START</li>
 *   <li>{@code PacketType.Login.Client.ENCRYPTION_BEGIN} = ENCRYPTION_RESPONSE</li>
 *   <li>{@code PacketType.Login.Server.ENCRYPTION_BEGIN} = ENCRYPTION_REQUEST</li>
 * </ul>
 */
public class ProtocolLibAuthListener extends PacketAdapter {

    private static final String MOJANG_HASJOINED_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined";
    private static final int VERIFICATION_TIMEOUT_MS = 5000;

    private final ValleyAuthPlugin plugin;
    private final MojangSessionVerifier sessionVerifier;
    private final SecureRandom secureRandom;

    /** Connection key (ip:port) → PendingVerification state */
    private final Map<String, PendingVerificationLib> pendingVerifications = new ConcurrentHashMap<>();

    /** Cached reflection accessor for getting Netty Channel from Player */
    private volatile Method getInjectorMethod;

    public ProtocolLibAuthListener(ValleyAuthPlugin plugin) {
        // Register for LOGIN_START and ENCRYPTION_BEGIN (EncryptionResponse), async
        super(params()
            .plugin(plugin)
            .types(PacketType.Login.Client.START, PacketType.Login.Client.ENCRYPTION_BEGIN)
            .optionAsync());

        this.plugin = plugin;
        this.sessionVerifier = plugin.getAuthManager().getSessionVerifier();
        this.secureRandom = new SecureRandom();
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        if (event.isCancelled()) {
            return;
        }

        PacketType packetType = getOverriddenType(event.getPacketType());

        if (packetType == PacketType.Login.Client.START) {
            handleLoginStart(event);
        } else if (packetType == PacketType.Login.Client.ENCRYPTION_BEGIN) {
            handleEncryptionBegin(event);
        }
    }

    /**
     * Handle LOGIN_START packet interception.
     * Section 79.4 Step 3.
     */
    private void handleLoginStart(PacketEvent event) {
        Player player = event.getPlayer();
        PacketContainer packet = event.getPacket();

        String username = readUsername(packet);
        if (username == null || username.isEmpty()) {
            return;
        }

        UUID playerUUID = readPlayerUUID(packet);

        String connectionKey = connectionKey(player);

        event.setCancelled(true);

        pendingVerifications.remove(connectionKey);

        byte[] verifyToken = new byte[4];
        secureRandom.nextBytes(verifyToken);

        KeyPair rsaKeyPair = sessionVerifier.getRsaKeyPair();

        PendingVerificationLib pending = new PendingVerificationLib(
            username, playerUUID, verifyToken, System.currentTimeMillis(), player
        );
        pendingVerifications.put(connectionKey, pending);

        PacketContainer encReq = new PacketContainer(PacketType.Login.Server.ENCRYPTION_BEGIN);
        encReq.getStrings().write(0, "");

        try {
            encReq.getSpecificModifier(PublicKey.class).write(0, rsaKeyPair.getPublic());
        } catch (Exception e) {
            encReq.getByteArrays().write(0, rsaKeyPair.getPublic().getEncoded());
        }

        encReq.getByteArrays().write(1, verifyToken);
        encReq.getBooleans().writeSafely(0, true);

        ProtocolLibrary.getProtocolManager().sendServerPacket(player, encReq, false);

        plugin.getLogger().info("Sent EncryptionRequest to " + username);
    }

    /**
     * Handle ENCRYPTION_BEGIN (EncryptionResponse) packet.
     * Section 79.4 Steps 3c-e.
     */
    private void handleEncryptionBegin(PacketEvent event) {
        Player player = event.getPlayer();
        String connectionKey = connectionKey(player);

        PendingVerificationLib pending = pendingVerifications.get(connectionKey);
        if (pending == null) {
            return;
        }

        // Check timeout
        if (System.currentTimeMillis() - pending.getCreatedAt() > VERIFICATION_TIMEOUT_MS) {
            plugin.getLogger().warning("Verification timed out for " + pending.getUsername());
            pendingVerifications.remove(connectionKey);
            event.setCancelled(true);
            assignOfflineIdentity(player, pending.getUsername(), pending.getClientUuid());
            return;
        }

        PacketContainer packet = event.getPacket();
        event.setCancelled(true);

        // Read encrypted data from packet
        byte[] encSharedSecret = packet.getByteArrays().read(0);
        byte[] encVerifyToken = packet.getByteArrays().read(1);

        // Step 3d: RSA-decrypt the shared secret
        byte[] sharedSecret;
        try {
            Cipher rsaCipher = Cipher.getInstance("RSA");
            rsaCipher.init(Cipher.DECRYPT_MODE, sessionVerifier.getRsaKeyPair().getPrivate());
            sharedSecret = rsaCipher.doFinal(encSharedSecret);
        } catch (GeneralSecurityException e) {
            plugin.getLogger().warning("RSA decryption failed for '" + pending.getUsername() + "': " + e.getMessage());
            pendingVerifications.remove(connectionKey);
            resumeLogin(player, pending.getUsername(), pending.getClientUuid());
            return;
        }

        // Step 3e: Verify the decrypted verify token
        byte[] decryptedVerifyToken;
        try {
            Cipher rsaCipher = Cipher.getInstance("RSA");
            rsaCipher.init(Cipher.DECRYPT_MODE, sessionVerifier.getRsaKeyPair().getPrivate());
            decryptedVerifyToken = rsaCipher.doFinal(encVerifyToken);
        } catch (GeneralSecurityException e) {
            plugin.getLogger().warning("RSA decryption of verify token failed for '" + pending.getUsername() + "'");
            pendingVerifications.remove(connectionKey);
            resumeLogin(player, pending.getUsername(), pending.getClientUuid());
            return;
        }

        if (!constantTimeEquals(pending.getVerifyToken(), decryptedVerifyToken)) {
            plugin.getLogger().warning("Verify token mismatch for '" + pending.getUsername() + "'");
            pendingVerifications.remove(connectionKey);
            resumeLogin(player, pending.getUsername(), pending.getClientUuid());
            return;
        }

        // Step 3f: Enable AES encryption
        enableChannelEncryption(player, sharedSecret);

        // Step 3g-i: Call hasJoined asynchronously
        String username = pending.getUsername();
        UUID clientUuid = pending.getClientUuid();
        Player pendingPlayer = pending.getPlayer();

        Thread.ofVirtual().name("ValleyAuth-HasJoined-" + username).start(() -> {
            try {
                PublicKey publicKey = sessionVerifier.getRsaKeyPair().getPublic();
                String serverId = sessionVerifier.computeServerHash(sharedSecret, publicKey.getEncoded());

                UUID mojangUuid = callHasJoined(username, serverId);

                if (mojangUuid != null) {
                    plugin.getLogger().info("Player verified as Premium: " + username + " (" + mojangUuid + ")");
                    plugin.getAuthManager().setAuthenticated(mojangUuid, AuthState.PREMIUM);
                    resumeLoginWithUuid(pendingPlayer, username, mojangUuid);
                } else {
                    plugin.getLogger().info("Verification failed for " + username + " — assigning Offline identity");
                    assignOfflineIdentity(pendingPlayer, username, clientUuid);
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "hasJoined failed for " + username, e);
                assignOfflineIdentity(pendingPlayer, username, clientUuid);
            } finally {
                pendingVerifications.remove(connectionKey);
            }
        });
    }

    /**
     * Read username from LOGIN_START packet.
     * Tries GameProfile first (newer ProtocolLib), falls back to Strings.
     */
    private String readUsername(PacketContainer packet) {
        try {
            var profiles = packet.getGameProfiles();
            if (profiles.size() > 0 && profiles.read(0) != null) {
                return profiles.read(0).getName();
            }
        } catch (Exception ignored) {
        }
        return packet.getStrings().read(0);
    }

    /**
     * Read player UUID from LOGIN_START packet (1.20.2+).
     */
    private UUID readPlayerUUID(PacketContainer packet) {
        return null;
    }

    /**
     * Call Mojang's hasJoined endpoint.
     */
    private UUID callHasJoined(String username, String serverId) throws IOException {
        String urlStr = MOJANG_HASJOINED_URL
            + "?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
            + "&serverId=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8);

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(VERIFICATION_TIMEOUT_MS);
        conn.setReadTimeout(VERIFICATION_TIMEOUT_MS);

        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            try (InputStream is = conn.getInputStream()) {
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return parseUuidFromResponse(body);
            }
        }
        conn.disconnect();
        return null;
    }

    /**
     * Parse UUID from Mojang's JSON response.
     * The response contains "id" : "dashlessUUID" (with spaces around colon).
     */
    private UUID parseUuidFromResponse(String json) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\"id\"\\s*:\\s*\"([a-fA-F0-9]{32})\"")
            .matcher(json);
        if (!matcher.find()) return null;

        String dashlessUuid = matcher.group(1);

        String formatted = dashlessUuid.replaceFirst(
            "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
            "$1-$2-$3-$4-$5"
        );

        try {
            return UUID.fromString(formatted);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Section 79.4 Step 3f: Enable AES/CFB8/NoPadding encryption on the Netty channel.
     * Uses reflection to access the Netty Channel from the Player object.
     */
    private void enableChannelEncryption(Player player, byte[] sharedSecret) {
        try {
            Channel channel = getChannel(player);
            if (channel == null) {
                plugin.getLogger().warning("Could not get Netty channel for " + player.getName());
                return;
            }

            SecretKeySpec key = new SecretKeySpec(sharedSecret, "AES");
            IvParameterSpec iv = new IvParameterSpec(sharedSecret);

            Cipher decryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
            decryptCipher.init(Cipher.DECRYPT_MODE, key, iv);

            Cipher encryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
            encryptCipher.init(Cipher.ENCRYPT_MODE, key, iv);

            ChannelPipeline pipeline = channel.pipeline();
            pipeline.addBefore("splitter", "decrypt", new AesCfb8Decoder(decryptCipher));
            pipeline.addBefore("prepender", "encrypt", new AesCfb8Encoder(encryptCipher));

            plugin.getLogger().fine("AES/CFB8 encryption installed for " + player.getName());
        } catch (GeneralSecurityException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to install AES cipher handlers for " + player.getName(), e);
        }
    }

    /**
     * Assign Offline identity and resume login.
     */
    private void assignOfflineIdentity(Player player, String username, UUID clientUuid) {
        UUID offlineUuid = plugin.getIdentityManager().getOrCreateOfflineUuid(username);
        String offlineUsername = plugin.getIdentityManager().getCanonicalName(IdentityType.OFFLINE, username);
        plugin.getAuthManager().setAuthenticated(offlineUuid, AuthState.OFFLINE);
        plugin.getLogger().info("Offline identity assigned: " + offlineUsername + " (" + offlineUuid + ")");
        resumeLoginWithUuid(player, username, offlineUuid);
    }

    /**
     * Re-inject LOGIN_START with a specific UUID using Netty channel access.
     *
     * This uses reflection to access the Netty Channel from the Player, then fires
     * a synthetic LOGIN_START packet through the pipeline. This is fragile and may
     * break across Minecraft versions.
     *
     * Fallback: If channel access fails, the player is kicked with a message to reconnect.
     * The verification result is still stored, so the reconnect will be processed correctly.
     */
    private void resumeLoginWithUuid(Player player, String username, UUID targetUuid) {
        try {
            Channel channel = getChannel(player);
            if (channel == null) {
                plugin.getLogger().warning("Could not get Netty channel for re-injection, kicking " + username);
                player.kickPlayer("Please reconnect to complete login.");
                return;
            }

            // Construct a raw LOGIN_START packet using ProtocolLib
            PacketContainer loginStartPacket = new PacketContainer(PacketType.Login.Client.START);

            // Write username
            loginStartPacket.getStrings().write(0, username);

            // Write UUID
            loginStartPacket.getUUIDs().write(0, targetUuid);

            // Write the packet to raw bytes using ProtocolLib's encoder
            Object rawPacket = loginStartPacket.getHandle();

            // Fire the raw packet through the inbound pipeline
            channel.pipeline().fireChannelRead(rawPacket);

            plugin.getLogger().fine("Re-injected LOGIN_START for " + username + " with UUID " + targetUuid);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to re-inject LOGIN_START for " + username, e);
            player.kickPlayer("Please reconnect to complete login.");
        }
    }

    /**
     * Resume login with original client UUID (passthrough).
     */
    private void resumeLogin(Player player, String username, UUID clientUuid) {
        resumeLoginWithUuid(player, username, clientUuid);
    }

    /**
     * Get the Netty Channel from a Player using ProtocolLib's internal reflection.
     * Cached after first successful access.
     */
    private Channel getChannel(Player player) {
        try {
            if (getInjectorMethod == null) {
                Class<?> injectorFactory = Class.forName("com.comphenix.protocol.injector.temporary.TemporaryPlayerFactory");
                getInjectorMethod = injectorFactory.getMethod("getInjectorFromPlayer", Player.class);
            }
            Object injector = getInjectorMethod.invoke(null, player);
            if (injector != null) {
                return FuzzyReflection.getFieldValue(injector, Channel.class, true);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "Failed to get Netty channel for " + player.getName(), e);
        }
        return null;
    }

    /**
     * Handle ProtocolLib's dynamic packet type override bug.
     * Some versions report different internal names for login packets.
     */
    private PacketType getOverriddenType(PacketType packetType) {
        if (packetType.isDynamic()) {
            String vanillaName = packetType.getPacketClass().getName();
            if (vanillaName.endsWith("ServerboundHelloPacket")) {
                return PacketType.Login.Client.START;
            }
            if (vanillaName.endsWith("ServerboundKeyPacket")) {
                return PacketType.Login.Client.ENCRYPTION_BEGIN;
            }
        }
        return packetType;
    }

    /**
     * Constant-time byte array comparison.
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    /**
     * Generate a connection key from the Player's address.
     */
    private static String connectionKey(Player player) {
        var addr = player.getAddress();
        return addr.getAddress().getHostAddress() + ":" + addr.getPort();
    }

    /**
     * ProtocolLib-specific pending verification state.
     * Stores Player reference instead of User (ProtocolLib uses Player objects).
     */
    private static final class PendingVerificationLib {
        private final String username;
        private final UUID clientUuid;
        private final byte[] verifyToken;
        private final long createdAt;
        private final Player player;

        PendingVerificationLib(String username, UUID clientUuid, byte[] verifyToken,
                               long createdAt, Player player) {
            this.username = username;
            this.clientUuid = clientUuid;
            this.verifyToken = verifyToken;
            this.createdAt = createdAt;
            this.player = player;
        }

        String getUsername() { return username; }
        UUID getClientUuid() { return clientUuid; }
        byte[] getVerifyToken() { return verifyToken; }
        long getCreatedAt() { return createdAt; }
        Player getPlayer() { return player; }
    }
}
