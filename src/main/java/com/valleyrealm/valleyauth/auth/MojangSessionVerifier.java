package com.valleyrealm.valleyauth.auth;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.accessors.Accessors;
import com.comphenix.protocol.reflect.accessors.FieldAccessor;
import com.comphenix.protocol.reflect.accessors.MethodAccessor;
import com.comphenix.protocol.injector.temporary.TemporaryPlayerFactory;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.valleyrealm.valleyauth.ValleyAuth;
import com.valleyrealm.valleyauth.mojang.MojangApiClient;
import com.valleyrealm.valleyauth.mojang.MojangApiClient.MojangProfile;
import org.bukkit.entity.Player;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.Key;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Mojang session verification via ProtocolLib.
 *
 * Intercepts the login handshake (LOGIN_START → ENCRYPTION_REQUEST → ENCRYPTION_RESPONSE),
 * forces a Mojang session server check to prove Premium account ownership, and
 * re-injects a synthetic LOGIN_START with the verified Mojang UUID.
 *
 * Fixes applied:
 * - Re-entry guard: receiveFakeStartPacket won't re-trigger handleLoginStart
 * - Failed verification cache: prevents infinite kick loop for cracked clients with Premium names
 * - Async marker handling: START event is properly released after verification
 * - Non-blocking I/O: HTTP calls use sendAsync() to avoid blocking Netty threads
 * - Instance-level reflection caches: safe across plugin reloads
 */
public class MojangSessionVerifier {

    private final ValleyAuth plugin;
    private final MojangApiClient mojangClient;
    private ProtocolManager protocolManager;
    private boolean enabled = false;

    private KeyPair serverKeyPair;
    private final Map<UUID, PendingSession> pendingSessions = new ConcurrentHashMap<>();
    private final Map<UUID, VerifiedSession> verifiedSessions = new ConcurrentHashMap<>();

    /**
     * Tracks usernames that failed Mojang session verification.
     * Prevents infinite kick loop: cracked client with Premium username gets one attempt,
     * then subsequent connections skip encryption request and fall through to Offline flow.
     * Cleared on plugin reload (implicit via shutdown/reinitialize).
     */
    private final Set<String> failedVerifications = ConcurrentHashMap.newKeySet();

    // Reflection caches — instance-level, not static, to avoid stale references across reloads
    private Method encryptMethod;
    private Method cipherFactoryMethod;

    // Shared SecureRandom instance (thread-safe, re-seeds internally)
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public MojangSessionVerifier(ValleyAuth plugin) {
        this.plugin = plugin;
        this.mojangClient = plugin.getMojangApiClient();
    }

    public boolean initialize() {
        try {
            protocolManager = ProtocolLibrary.getProtocolManager();

            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(1024);
            serverKeyPair = keyGen.generateKeyPair();

            registerPacketListeners();
            enabled = true;
            plugin.getLogger().info("[Valley Auth] Mojang session verifier enabled (ProtocolLib).");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Valley Auth] Failed to initialize Mojang session verifier", e);
            return false;
        }
    }

    private void registerPacketListeners() {
        protocolManager.addPacketListener(new PacketAdapter(plugin,
                PacketType.Login.Client.START) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handleLoginStart(event);
            }
        });

        protocolManager.addPacketListener(new PacketAdapter(plugin,
                PacketType.Login.Client.ENCRYPTION_BEGIN) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                handleEncryptionResponse(event);
            }
        });
    }

    /**
     * Handle LOGIN_START packet.
     *
     * FIX: Re-entry guard prevents receiveFakeStartPacket from re-triggering this method.
     * FIX: Failed verification cache prevents infinite kick loop for cracked clients.
     * FIX: Async HTTP call avoids blocking Netty event loop.
     */
    private void handleLoginStart(PacketEvent event) {
        if (!enabled) return;

        Player player = event.getPlayer();
        String username = getUsername(event);
        UUID playerUuid = player.getUniqueId();

        if (username == null || mojangClient == null || !mojangClient.isEnabled()) return;

        // FIX: Re-entry guard — if we already verified or are verifying, skip
        if (verifiedSessions.containsKey(playerUuid) || pendingSessions.containsKey(playerUuid)) return;

        // FIX: Failed verification cache — if this username already failed, skip to Offline flow
        String usernameKey = username.toLowerCase();
        if (failedVerifications.contains(usernameKey)) return;

        // Check if Mojang API says this username exists (async to avoid blocking Netty)
        mojangClient.isPremiumUsernameAsync(username).thenAccept(exists -> {
            if (!exists) return; // Not a Premium username, let vanilla handle it

            // Run back on main thread for ProtocolLib packet manipulation
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!enabled) return;

                // Re-check state in case it changed while async
                if (verifiedSessions.containsKey(playerUuid) || pendingSessions.containsKey(playerUuid)) return;
                if (failedVerifications.contains(usernameKey)) return;

                byte[] verifyToken = new byte[4];
                SECURE_RANDOM.nextBytes(verifyToken);

                // Store async marker for later release
                PendingSession pending = new PendingSession(username, playerUuid, verifyToken, event);
                pendingSessions.put(playerUuid, pending);

                try {
                    sendEncryptionRequest(player, verifyToken);
                    plugin.getLogger().info("[Valley Auth] Sent encryption request to " + username);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "[Valley Auth] Failed to send encryption request for " + username, e);
                    pendingSessions.remove(playerUuid);
                    player.kickPlayer("§c[Valley Auth] Authentication failed.");
                }
            });
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "[Valley Auth] Async username check failed for " + username, ex);
            return null;
        });
    }

    private String getUsername(PacketEvent event) {
        PacketContainer packet = event.getPacket();
        try {
            WrappedGameProfile profile = packet.getGameProfiles().read(0);
            if (profile != null && profile.getName() != null) {
                return profile.getName();
            }
        } catch (Exception ignored) {}
        return packet.getStrings().read(0);
    }

    private void sendEncryptionRequest(Player player, byte[] verifyToken) {
        PacketContainer packet = new PacketContainer(PacketType.Login.Server.ENCRYPTION_BEGIN);

        packet.getStrings().write(0, "");

        var keyModifier = packet.getSpecificModifier(PublicKey.class);
        int verifyField = 0;
        if (keyModifier.getFields().isEmpty()) {
            packet.getByteArrays().write(0, serverKeyPair.getPublic().getEncoded());
            verifyField++;
        } else {
            keyModifier.write(0, serverKeyPair.getPublic());
        }

        packet.getByteArrays().write(verifyField, verifyToken);

        protocolManager.sendServerPacket(player, packet);
    }

    /**
     * Handle ENCRYPTION_BEGIN (client's encryption response).
     *
     * FIX: Async HTTP call for verifySession avoids blocking Netty.
     * FIX: Releases START event's async marker after verification.
     * FIX: Failed verification caches username to prevent kick loop.
     * FIX: receiveFakeStartPacket failure kicks player instead of leaving in limbo.
     */
    private void handleEncryptionResponse(PacketEvent event) {
        if (!enabled) return;

        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        PendingSession pending = pendingSessions.remove(playerUuid);

        if (pending == null) return;

        try {
            // Decrypt verify token
            byte[] encryptedVerifyToken = event.getPacket().getByteArrays().read(0);
            Cipher cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.DECRYPT_MODE, serverKeyPair.getPrivate());
            byte[] clientVerifyToken = cipher.doFinal(encryptedVerifyToken);

            if (!MessageDigest.isEqual(pending.verifyToken, clientVerifyToken)) {
                plugin.getLogger().warning("[Valley Auth] Verify token mismatch for " + pending.username);
                event.setCancelled(true);
                releaseAsyncMarker(pending);
                player.kickPlayer("§c[Valley Auth] Authentication failed.");
                return;
            }

            // Decrypt shared secret
            byte[] encryptedSharedSecret = event.getPacket().getByteArrays().read(1);
            cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.DECRYPT_MODE, serverKeyPair.getPrivate());
            byte[] sharedSecret = cipher.doFinal(encryptedSharedSecret);

            SecretKey loginKey = new SecretKeySpec(sharedSecret, "AES");

            // Enable encryption on the connection
            if (!enableEncryption(player, loginKey)) {
                event.setCancelled(true);
                releaseAsyncMarker(pending);
                player.kickPlayer("§c[Valley Auth] Encryption setup failed.");
                return;
            }

            // Verify with Mojang session server (async to avoid blocking Netty)
            String serverId = createServerIdHash(sharedSecret);

            mojangClient.verifySessionAsync(pending.username, serverId).thenAccept(profile -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (profile != null) {
                        plugin.getLogger().info("[Valley Auth] Mojang session verified: " + pending.username + " (" + profile.uuid() + ")");
                        verifiedSessions.put(playerUuid, new VerifiedSession(pending.username, profile.uuid(), true));
                        spoofUuid(player, profile.uuid());

                        // FIX: Release START event's async marker BEFORE re-injecting
                        releaseAsyncMarker(pending);

                        // Re-inject LOGIN_START with verified Mojang UUID
                        if (!receiveVerifiedStartPacket(player, pending.username, profile.uuid())) {
                            // FIX: If re-injection fails, kick player instead of leaving in limbo
                            plugin.getLogger().warning("[Valley Auth] Failed to re-inject LOGIN_START for verified player " + pending.username);
                            verifiedSessions.remove(playerUuid);
                            player.kickPlayer("§c[Valley Auth] Internal error — please reconnect.");
                        }
                        event.setCancelled(true);
                    } else {
                        // FIX: Cache failed verification to prevent kick loop
                        plugin.getLogger().info("[Valley Auth] Mojang session failed for " + pending.username + " — Offline.");
                        failedVerifications.add(pending.username.toLowerCase());
                        verifiedSessions.put(playerUuid, new VerifiedSession(pending.username, null, false));
                        event.setCancelled(true);
                        releaseAsyncMarker(pending);
                        player.kickPlayer("§c[Valley Auth] Mojang authentication failed. Reconnect to play as Offline.");
                    }
                });
            }).exceptionally(ex -> {
                plugin.getLogger().log(Level.WARNING, "[Valley Auth] Async session verification failed for " + pending.username, ex);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    failedVerifications.add(pending.username.toLowerCase());
                    verifiedSessions.put(playerUuid, new VerifiedSession(pending.username, null, false));
                    event.setCancelled(true);
                    releaseAsyncMarker(pending);
                    player.kickPlayer("§c[Valley Auth] Authentication failed.");
                });
                return null;
            });
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Valley Auth] Error processing encryption response", e);
            event.setCancelled(true);
            releaseAsyncMarker(pending);
            player.kickPlayer("§c[Valley Auth] Authentication failed.");
        }
    }

    /**
     * Release the async marker from the original LOGIN_START event.
     * FIX: Prevents async marker leak that was blocking the packet pipeline.
     */
    private void releaseAsyncMarker(PendingSession pending) {
        if (pending.startEvent != null) {
            try {
                protocolManager.getAsynchronousManager().signalPacketTransmission(pending.startEvent);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[Valley Auth] Failed to release async marker", e);
            }
        }
    }

    private boolean enableEncryption(Player player, SecretKey loginKey) {
        try {
            if (encryptMethod == null) {
                Class<?> networkManagerClass = MinecraftReflection.getNetworkManagerClass();
                encryptMethod = findMethodByParams(networkManagerClass, Cipher.class, Cipher.class);
                if (encryptMethod == null) {
                    encryptMethod = findMethodByParams(networkManagerClass, SecretKey.class);
                }
            }

            if (cipherFactoryMethod == null) {
                try {
                    Class<?> encClass = MinecraftReflection.getMinecraftClass(
                            "util.MinecraftEncryption", "MinecraftEncryption");
                    cipherFactoryMethod = findStaticMethodByParams(encClass, int.class, Key.class);
                } catch (Exception ignored) {}
            }

            Object networkManager = getNetworkManager(player);
            if (networkManager == null) {
                plugin.getLogger().warning("[Valley Auth] Could not get network manager");
                return false;
            }

            if (encryptMethod == null) {
                plugin.getLogger().warning("[Valley Auth] Could not find encryption method on " + networkManager.getClass().getName());
                return false;
            }

            Class<?>[] paramTypes = encryptMethod.getParameterTypes();
            if (paramTypes.length == 2 && paramTypes[0] == Cipher.class) {
                if (cipherFactoryMethod == null) {
                    Cipher decCipher = Cipher.getInstance("AES/CFB8/NoPadding");
                    decCipher.init(Cipher.DECRYPT_MODE, loginKey);
                    Cipher encCipher = Cipher.getInstance("AES/CFB8/NoPadding");
                    encCipher.init(Cipher.ENCRYPT_MODE, loginKey);
                    encryptMethod.invoke(networkManager, decCipher, encCipher);
                } else {
                    Object decCipher = cipherFactoryMethod.invoke(null, Cipher.DECRYPT_MODE, loginKey);
                    Object encCipher = cipherFactoryMethod.invoke(null, Cipher.ENCRYPT_MODE, loginKey);
                    encryptMethod.invoke(networkManager, decCipher, encCipher);
                }
            } else {
                encryptMethod.invoke(networkManager, loginKey);
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Valley Auth] Failed to enable encryption", e);
            return false;
        }
    }

    private void spoofUuid(Player player, UUID premiumUuid) {
        try {
            Object networkManager = getNetworkManager(player);
            if (networkManager == null) return;
            FieldAccessor accessor = Accessors.getFieldAccessorOrNull(
                    networkManager.getClass(), "spoofedUUID", UUID.class);
            if (accessor != null) {
                accessor.set(networkManager, premiumUuid);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Valley Auth] Failed to spoof UUID", e);
        }
    }

    /**
     * Re-inject a LOGIN_START packet with the verified Mojang UUID.
     *
     * FIX: Renamed from receiveFakeStartPacket for clarity.
     * FIX: Returns boolean to indicate success/failure for recovery.
     * FIX: Passes filters=false to prevent re-triggering handleLoginStart.
     *
     * @return true if the packet was successfully re-injected
     */
    private boolean receiveVerifiedStartPacket(Player player, String username, UUID uuid) {
        try {
            PacketContainer startPacket = new PacketContainer(PacketType.Login.Client.START);
            startPacket.getGameProfiles().write(0, new WrappedGameProfile(uuid, username));
            // FIX: filters=false prevents re-triggering handleLoginStart (re-entry guard is also in place)
            protocolManager.receiveClientPacket(player, startPacket, false);
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Valley Auth] Failed to re-inject LOGIN_START", e);
            return false;
        }
    }

    private Object getNetworkManager(Player player) {
        try {
            MethodAccessor injectorAccessor = Accessors.getMethodAccessorOrNull(
                    TemporaryPlayerFactory.class, "getInjectorFromPlayer", Player.class);
            if (injectorAccessor == null) return null;
            Object injector = injectorAccessor.invoke(null, player);
            if (injector == null) return null;
            FieldAccessor accessor = Accessors.getFieldAccessorOrNull(
                    injector.getClass(), "networkManager", Object.class);
            return accessor != null ? accessor.get(injector) : null;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Valley Auth] Failed to get network manager", e);
            return null;
        }
    }

    private String createServerIdHash(byte[] sharedSecret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update("".getBytes("UTF-8"));
            digest.update(sharedSecret);
            digest.update(serverKeyPair.getPublic().getEncoded());
            byte[] hash = digest.digest();
            BigInteger bigInt = new BigInteger(1, hash);
            String hex = bigInt.toString(16);
            while (hex.length() < 32) hex = "0" + hex;
            return hex;
        } catch (Exception e) {
            return "";
        }
    }

    private static Method findMethodByParams(Class<?> clazz, Class<?>... paramTypes) {
        for (Method m : clazz.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length == paramTypes.length) {
                boolean match = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    if (!params[i].equals(paramTypes[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) return m;
            }
        }
        for (Method m : clazz.getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length == paramTypes.length) {
                boolean match = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    if (!params[i].equals(paramTypes[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) return m;
            }
        }
        return null;
    }

    private static Method findStaticMethodByParams(Class<?> clazz, Class<?>... paramTypes) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length == paramTypes.length) {
                boolean match = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    if (!params[i].equals(paramTypes[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) return m;
            }
        }
        return null;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    public boolean isSessionVerified(UUID playerUuid) {
        VerifiedSession session = verifiedSessions.get(playerUuid);
        return session != null && session.verified;
    }

    public UUID getVerifiedMojangUuid(UUID playerUuid) {
        VerifiedSession session = verifiedSessions.get(playerUuid);
        return (session != null && session.verified) ? session.mojangUuid : null;
    }

    public String getVerifiedUsername(UUID playerUuid) {
        VerifiedSession session = verifiedSessions.get(playerUuid);
        return session != null ? session.username : null;
    }

    public void cleanupSession(UUID playerUuid) {
        pendingSessions.remove(playerUuid);
        verifiedSessions.remove(playerUuid);
    }

    public boolean isEnabled() { return enabled; }

    public void shutdown() {
        enabled = false;
        pendingSessions.clear();
        verifiedSessions.clear();
        failedVerifications.clear();
        if (protocolManager != null) protocolManager.removePacketListeners(plugin);
    }

    // ── Inner classes ───────────────────────────────────────────────────────

    private static class PendingSession {
        final String username;
        final UUID playerUuid;
        final byte[] verifyToken;
        final PacketEvent startEvent;

        PendingSession(String username, UUID playerUuid, byte[] verifyToken,
                       PacketEvent startEvent) {
            this.username = username;
            this.playerUuid = playerUuid;
            this.verifyToken = verifyToken;
            this.startEvent = startEvent;
        }
    }

    public static class VerifiedSession {
        public final String username;
        public final UUID mojangUuid;
        public final boolean verified;

        VerifiedSession(String username, UUID mojangUuid, boolean verified) {
            this.username = username;
            this.mojangUuid = mojangUuid;
            this.verified = verified;
        }
    }
}
