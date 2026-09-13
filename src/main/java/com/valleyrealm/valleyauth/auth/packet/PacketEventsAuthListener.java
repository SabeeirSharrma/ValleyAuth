package com.valleyrealm.valleyauth.auth.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import com.valleyrealm.valleyauth.ValleyAuthPlugin;
import com.valleyrealm.valleyauth.auth.AuthState;
import com.valleyrealm.valleyauth.identity.IdentityType;
import com.valleyrealm.valleyauth.session.MojangSessionVerifier;
import io.netty.channel.ChannelPipeline;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * packetevents-based packet interceptor for forced Mojang verification.
 * Section 79.4 Steps 3-5 of the spec.
 *
 * Flow:
 * 1. Intercept LOGIN_START → Cancel it, check Mojang API for account
 * 2. If account exists → Send ENCRYPTION_REQUEST, store pending verification
 * 3. Intercept ENCRYPTION_RESPONSE → RSA-decrypt, verify token, enable AES
 * 4. Call Mojang hasJoined → Premium identity or Offline identity
 * 5. Re-inject LOGIN_START with appropriate UUID
 *
 * Timeout: 5 seconds for the entire verification. Fail → Offline identity.
 * Disconnect mid-check: Cancel everything, write nothing.
 */
public class PacketEventsAuthListener extends PacketListenerAbstract {

    private static final String MOJANG_HASJOINED_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined";
    private static final int VERIFICATION_TIMEOUT_MS = 5000;

    private final ValleyAuthPlugin plugin;
    private final MojangSessionVerifier sessionVerifier;
    private final SecureRandom secureRandom;

    /** Connection key (ip:port) → PendingVerification state */
    private final Map<String, PendingVerification> pendingVerifications = new ConcurrentHashMap<>();

    public PacketEventsAuthListener(ValleyAuthPlugin plugin) {
        this.plugin = plugin;
        this.sessionVerifier = plugin.getAuthManager().getSessionVerifier();
        this.secureRandom = new SecureRandom();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Login.Client.LOGIN_START) {
            handleLoginStart(event);
        } else if (event.getPacketType() == PacketType.Login.Client.ENCRYPTION_RESPONSE) {
            handleEncryptionResponse(event);
        }
    }

    /**
     * Section 79.4 Step 3: Intercept LOGIN_START.
     *
     * Cancel the packet so vanilla doesn't process it.
     * Check Mojang API for a registered account.
     * If account exists → send EncryptionRequest, store pending state.
     * If no account → re-inject LOGIN_START with Offline identity.
     */
    private void handleLoginStart(PacketReceiveEvent event) {
        WrapperLoginClientLoginStart wrapper = new WrapperLoginClientLoginStart(event);
        String username = wrapper.getUsername();
        if (username == null || username.isEmpty()) {
            return;
        }

        User user = event.getUser();
        ClientVersion clientVersion = user.getClientVersion();
        String connectionKey = connectionKey(user);
        UUID playerUUID = wrapper.getPlayerUUID().orElse(null);

        event.setCancelled(true);

        pendingVerifications.remove(connectionKey);

        byte[] verifyToken = new byte[4];
        secureRandom.nextBytes(verifyToken);

        KeyPair rsaKeyPair = sessionVerifier.getRsaKeyPair();

        PendingVerification pending = new PendingVerification(
            username, playerUUID, verifyToken,
            clientVersion, System.currentTimeMillis(), user
        );
        pendingVerifications.put(connectionKey, pending);

        WrapperLoginServerEncryptionRequest encReq = new WrapperLoginServerEncryptionRequest(
            "",
            rsaKeyPair.getPublic(),
            verifyToken,
            true
        );
        user.sendPacket(encReq);

        plugin.getLogger().info("Sent EncryptionRequest to " + username);
    }

    /**
     * Section 79.4 Step 3c-e: Intercept ENCRYPTION_RESPONSE.
     *
     * RSA-decrypt the shared secret and verify token.
     * Verify the decrypted verify token matches what we sent.
     * Enable AES/CFB8/NoPadding encryption on the Netty channel.
     * Call Mojang's hasJoined endpoint.
     * Re-inject LOGIN_START with Premium or Offline identity.
     */
    private void handleEncryptionResponse(PacketReceiveEvent event) {
        User user = event.getUser();
        String connectionKey = connectionKey(user);

        PendingVerification pending = pendingVerifications.get(connectionKey);
        if (pending == null) {
            return;
        }

        // Check timeout
        if (System.currentTimeMillis() - pending.getCreatedAt() > VERIFICATION_TIMEOUT_MS) {
            plugin.getLogger().warning("Verification timed out for " + pending.getUsername());
            pendingVerifications.remove(connectionKey);
            event.setCancelled(true);
            assignOfflineIdentity(user, pending.getUsername(), pending.getClientVersion(), pending.getClientUuid());
            return;
        }

        WrapperLoginClientEncryptionResponse wrapper = new WrapperLoginClientEncryptionResponse(event);

        Optional<byte[]> encVerifyTokenOpt = wrapper.getEncryptedVerifyToken();
        if (encVerifyTokenOpt.isEmpty()) {
            plugin.getLogger().warning("Player '" + pending.getUsername()
                + "' returned signed nonce during verification (expected plain verify token). "
                + "Resuming without premium bypass.");
            pendingVerifications.remove(connectionKey);
            event.setCancelled(true);
            resumeLogin(user, pending.getUsername(), pending.getClientVersion(), pending.getClientUuid());
            return;
        }

        byte[] encSharedSecret = wrapper.getEncryptedSharedSecret().clone();
        byte[] encVerifyToken = encVerifyTokenOpt.get().clone();
        event.setCancelled(true);

        // Step 3d: RSA-decrypt the shared secret synchronously (fast; we're on the event loop).
        // Must do this here — client is already in AES-encrypted mode after sending
        // ENCRYPTION_RESPONSE; without decryption on our side every subsequent packet
        // arrives as garbled bytes.
        byte[] sharedSecret;
        try {
            Cipher rsaCipher = Cipher.getInstance("RSA");
            rsaCipher.init(Cipher.DECRYPT_MODE, sessionVerifier.getRsaKeyPair().getPrivate());
            sharedSecret = rsaCipher.doFinal(encSharedSecret);
        } catch (GeneralSecurityException e) {
            plugin.getLogger().warning("RSA decryption failed for '" + pending.getUsername() + "': " + e.getMessage());
            pendingVerifications.remove(connectionKey);
            resumeLogin(user, pending.getUsername(), pending.getClientVersion(), pending.getClientUuid());
            return;
        }

        // Step 3e: Decrypt and verify the verify token matches what we sent
        byte[] decryptedVerifyToken;
        try {
            Cipher rsaCipher = Cipher.getInstance("RSA");
            rsaCipher.init(Cipher.DECRYPT_MODE, sessionVerifier.getRsaKeyPair().getPrivate());
            decryptedVerifyToken = rsaCipher.doFinal(encVerifyToken);
        } catch (GeneralSecurityException e) {
            plugin.getLogger().warning("RSA decryption of verify token failed for '" + pending.getUsername() + "'");
            pendingVerifications.remove(connectionKey);
            resumeLogin(user, pending.getUsername(), pending.getClientVersion(), pending.getClientUuid());
            return;
        }

        byte[] expectedToken = pending.getVerifyToken();
        if (!constantTimeEquals(expectedToken, decryptedVerifyToken)) {
            plugin.getLogger().warning("Verify token mismatch for '" + pending.getUsername() + "'");
            pendingVerifications.remove(connectionKey);
            resumeLogin(user, pending.getUsername(), pending.getClientVersion(), pending.getClientUuid());
            return;
        }

        // Step 3f: Enable AES/CFB8/NoPadding encryption on the Netty channel
        enableChannelEncryption(user.getChannel(), sharedSecret);

        // Step 3g-i: Call Mojang's hasJoined endpoint asynchronously
        String username = pending.getUsername();
        UUID clientUuid = pending.getClientUuid();
        ClientVersion clientVersion = pending.getClientVersion();
        User pendingUser = pending.getUser();

        Thread.ofVirtual().name("ValleyAuth-HasJoined-" + username).start(() -> {
            try {
                PublicKey publicKey = sessionVerifier.getRsaKeyPair().getPublic();
                String serverId = sessionVerifier.computeServerHash(sharedSecret, publicKey.getEncoded());

                UUID mojangUuid = callHasJoined(username, serverId);

                if (mojangUuid != null) {
                    UUID paperUuid = paperOfflineUuid(username);
                    plugin.getLogger().info("Player verified as Premium: " + username + " (" + mojangUuid + ")");
                    plugin.getLogger().info("Storing auth under: mojangUuid=" + mojangUuid + ", paperUuid=" + paperUuid + ", clientUuid=" + clientUuid);
                    plugin.getAuthManager().setAuthenticated(mojangUuid, AuthState.PREMIUM);
                    plugin.getAuthManager().setAuthenticated(paperUuid, AuthState.PREMIUM);
                    if (clientUuid != null && !clientUuid.equals(mojangUuid)) {
                        plugin.getAuthManager().setAuthenticated(clientUuid, AuthState.PREMIUM);
                    }
                    resumeLoginWithUuid(pendingUser, username, clientVersion, mojangUuid);
                } else {
                    // Step 3j: Failed — re-inject LOGIN_START with Offline identity
                    plugin.getLogger().info("Verification failed for " + username + " — assigning Offline identity");
                    assignOfflineIdentity(pendingUser, username, clientVersion, clientUuid);
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "hasJoined failed for " + username, e);
                assignOfflineIdentity(pendingUser, username, clientVersion, clientUuid);
            } finally {
                pendingVerifications.remove(connectionKey);
            }
        });
    }

    /**
     * Call Mojang's hasJoined endpoint to verify a player's session.
     *
     * @return The player's real Mojang UUID if verified, null if failed (204 or error)
     */
    private @Nullable UUID callHasJoined(String username, String serverId) throws IOException {
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
    private @Nullable UUID parseUuidFromResponse(String json) {
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
     *
     * Must be called on the channel's event-loop thread (synchronously from onPacketReceive).
     * After the client sends ENCRYPTION_RESPONSE it immediately encrypts all outbound traffic,
     * so decryption must be in place before any subsequent packet arrives.
     *
     * Handler positions follow vanilla Minecraft's Connection.setupEncryption():
     * - decrypt before "splitter" (decrypt raw bytes before frame-splitting)
     * - encrypt before "prepender" (encrypt after length-prefixing, since the length varint
     *   is inside the encrypted stream in the Minecraft protocol)
     */
    private void enableChannelEncryption(Object channel, byte[] sharedSecret) {
        try {
            SecretKeySpec key = new SecretKeySpec(sharedSecret, "AES");
            // In Minecraft, the IV equals the shared secret (same 16 bytes for key and IV)
            IvParameterSpec iv = new IvParameterSpec(sharedSecret);

            Cipher decryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
            decryptCipher.init(Cipher.DECRYPT_MODE, key, iv);

            Cipher encryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
            encryptCipher.init(Cipher.ENCRYPT_MODE, key, iv);

            ChannelPipeline pipeline = (ChannelPipeline) ChannelHelper.getPipeline(channel);
            pipeline.addBefore("splitter", "decrypt", new AesCfb8Decoder(decryptCipher));
            pipeline.addBefore("prepender", "encrypt", new AesCfb8Encoder(encryptCipher));

            plugin.getLogger().fine("AES/CFB8 encryption installed for channel");
        } catch (GeneralSecurityException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to install AES cipher handlers", e);
        }
    }

    /**
     * Re-inject a LOGIN_START packet with the Offline identity so vanilla processes it normally.
     * Offline identity = "-username" prefix + ValleyAuth UUID.
     */
    private void assignOfflineIdentity(User user, String username, ClientVersion clientVersion, UUID clientUuid) {
        UUID offlineUuid = plugin.getIdentityManager().getOrCreateOfflineUuid(username);
        String offlineUsername = plugin.getIdentityManager().getCanonicalName(IdentityType.OFFLINE, username);
        plugin.getAuthManager().setAuthenticated(offlineUuid, AuthState.OFFLINE);
        plugin.getAuthManager().setAuthenticated(paperOfflineUuid(username), AuthState.OFFLINE);
        if (clientUuid != null && !clientUuid.equals(offlineUuid)) {
            plugin.getAuthManager().setAuthenticated(clientUuid, AuthState.OFFLINE);
        }
        plugin.getLogger().info("Offline identity assigned: " + offlineUsername + " (" + offlineUuid + ")");

        WrapperLoginClientLoginStart resumePacket = new WrapperLoginClientLoginStart(
            clientVersion, offlineUsername, null, offlineUuid
        );
        user.receivePacketSilently(resumePacket);
    }

    /**
     * Re-inject a LOGIN_START packet with the real Mojang UUID (Premium identity).
     * Uses receivePacketSilently to bypass PacketEvents' own listener chain
     * (prevents infinite interception of our own injected packet).
     *
     * The playerUUID must be forwarded for MC >= 1.20.2 clients.
     */
    private void resumeLoginWithUuid(User user, String username, ClientVersion clientVersion, UUID mojangUuid) {
        WrapperLoginClientLoginStart resumePacket = new WrapperLoginClientLoginStart(
            clientVersion, username, null, mojangUuid
        );
        user.receivePacketSilently(resumePacket);
    }

    /**
     * Re-inject LOGIN_START with the original client UUID (passthrough).
     * Used when verification fails or is skipped but we still need to resume login.
     */
    private void resumeLogin(User user, String username, ClientVersion clientVersion, UUID clientUuid) {
        WrapperLoginClientLoginStart resumePacket = new WrapperLoginClientLoginStart(
            clientVersion, username, null, clientUuid
        );
        user.receivePacketSilently(resumePacket);
    }

    /**
     * Constant-time byte array comparison to prevent timing attacks on verify token.
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
     * Generate a connection key from the User's address.
     * ip:port is unique for an incoming login request.
     */
    private static String connectionKey(User user) {
        var addr = user.getAddress();
        return addr.getAddress().getHostAddress() + ":" + addr.getPort();
    }

    private static UUID paperOfflineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
