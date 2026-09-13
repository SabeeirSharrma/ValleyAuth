package com.valleyrealm.valleyauth.auth;

import com.github.retrooper.packetevents.PacketEvents;
import com.valleyrealm.valleyauth.ValleyAuthPlugin;
import com.valleyrealm.valleyauth.auth.packet.PacketEventsAuthListener;
import com.valleyrealm.valleyauth.session.MojangSessionVerifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Core authentication manager.
 *
 * Implements Section 79 of the spec:
 * - Stage 79.1-79.2: Startup mode detection (handled in ValleyAuthPlugin)
 * - Stage 79.3: Online-mode TCore passthrough
 * - Stage 79.4: Offline-mode forced verification
 */
public class AuthManager implements Listener {

    private final ValleyAuthPlugin plugin;
    private final MojangSessionVerifier sessionVerifier;
    private final PasswordStore passwordStore;

    /** Player UUID → auth state (for online-mode DB sync tracking) */
    private final Map<UUID, AuthState> authStates = new ConcurrentHashMap<>();

    public AuthManager(ValleyAuthPlugin plugin) {
        this.plugin = plugin;
        this.sessionVerifier = new MojangSessionVerifier(plugin);
        this.passwordStore = new PasswordStore(plugin);
    }

    /**
     * Stage 79.4 Step 2: Register the appropriate packet listener.
     * Called from ValleyAuthPlugin.onEnable() after AuthManager construction.
     *
     * - packetevents: Register PacketEventsAuthListener with PacketEvents' EventManager
     * - ProtocolLib: Register ProtocolLibAuthListener as an async PacketAdapter
     * - NONE: No packet listener registered (forced auth disabled)
     */
    public void registerPacketListener() {
        PacketBackend backend = plugin.getPacketBackend();

        switch (backend) {
            case PACKETEVENTS -> {
                PacketEvents.getAPI().getEventManager().registerListener(
                    new PacketEventsAuthListener(plugin)
                );
                plugin.getLogger().info("Registered packetevents auth listener");
            }
            case PROTOCOL_LIB -> {
                try {
                    // Use reflection — ProtocolLib classes may not be on the classpath
                    Class<?> listenerClass = Class.forName(
                        "com.valleyrealm.valleyauth.auth.packet.ProtocolLibAuthListener");
                    Object listener = listenerClass.getConstructor(ValleyAuthPlugin.class).newInstance(plugin);

                    Class<?> protocolLibClass = Class.forName("com.comphenix.protocol.ProtocolLibrary");
                    Object protocolManager = protocolLibClass.getMethod("getProtocolManager").invoke(null);

                    Object asyncManager = protocolManager.getClass()
                        .getMethod("getAsynchronousManager").invoke(protocolManager);

                    Class<?> packetAdapterClass = Class.forName(
                        "com.comphenix.protocol.events.PacketAdapter");
                    if (!packetAdapterClass.isInstance(listener)) {
                        throw new IllegalStateException("ProtocolLibAuthListener is not a PacketAdapter subtype");
                    }

                    Object asyncHandler = asyncManager.getClass()
                        .getMethod("registerAsyncHandler", packetAdapterClass)
                        .invoke(asyncManager, listener);
                    asyncHandler.getClass().getMethod("start").invoke(asyncHandler);

                    plugin.getLogger().info("Registered ProtocolLib auth listener");
                } catch (ClassNotFoundException e) {
                    plugin.getLogger().log(Level.SEVERE,
                        "ProtocolLib not found — cannot register listener", e);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE,
                        "Failed to register ProtocolLib listener", e);
                }
            }
            case NONE -> {
                plugin.getLogger().info("No packet backend — forced auth disabled");
            }
        }
    }

    /**
     * Stage 79.3: Online-mode path ("TCore" behavior).
     *
     * When the server is in online-mode, Mojang/vanilla has already authenticated
     * the connection. Valley Auth never blocks or delays a login in this mode.
     * We just allow it.
     *
     * This event fires BEFORE the player joins — we don't need to do anything here
     * for online-mode. The DB sync happens on PlayerJoinEvent.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLoginOnlineMode(AsyncPlayerPreLoginEvent event) {
        if (plugin.isOnlineMode()) {
            // Section 79.3: Every join is allowed — no blocking, no delay
            event.allow();
        }
    }

    /**
     * Stage 79.4: Offline-mode path.
     *
     * This hooks at AsyncPlayerPreLoginEvent (off the main thread).
     * The flow:
     * 1. Check Floodgate → Bedrock identity
     * 2. Mojang lookup → is there a registered account?
     * 3. Forced session verification → prove ownership
     * 4. Success → Premium identity, Fail/Timeout → Offline identity
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLoginOfflineMode(AsyncPlayerPreLoginEvent event) {
        if (plugin.isOnlineMode()) {
            return; // Handled above
        }

        UUID playerUuid = event.getUniqueId();
        String username = event.getName();

        // Step 0: Floodgate check first
        if (plugin.isFloodgateEnabled()) {
            if (com.valleyrealm.valleyauth.floodgate.FloodgateHook.isBedrockPlayer(playerUuid)) {
                // Bedrock identity — Floodgate already added '.' prefix and assigned UUID
                authStates.put(playerUuid, AuthState.BEDROCK);
                event.allow();
                plugin.getLogger().info("Bedrock player detected: " + username);
                return;
            }
        }

        // Step 3: Mojang lookup — does this username have a registered account?
        if (plugin.getPacketBackend() == PacketBackend.NONE) {
            // No packet library → forced auth disabled → fail closed to Offline
            assignOfflineIdentity(event);
            return;
        }

        // Packet listener is handling the verification — allow the login to proceed
        // to the packet interception phase. The packet listener will cancel LOGIN_START
        // and handle the Mojang verification flow.
        event.allow();
    }

    /**
     * Stage 79.3: Online-mode DB sync on PlayerJoinEvent.
     *
     * The player is already in the world. We create/update our database record
     * for future migration bookkeeping. This happens AFTER the player joins,
     * never during the login sequence.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String username = player.getName();

        if (plugin.isOnlineMode()) {
            // Section 79.3: Premium identity — real Mojang UUID, no password needed
            authStates.put(uuid, AuthState.AUTHENTICATED);
            plugin.getLogger().info("Premium player joined: " + username + " (" + uuid + ")");
            // TODO: Create/update database record for migration bookkeeping
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // Don't remove auth state — keep for reconnect tracking
    }

    /**
     * Assign Offline identity to a player.
     * Stage 79.4 Step 6: -prefix + persistent ValleyAuth UUID.
     */
    public void assignOfflineIdentity(AsyncPlayerPreLoginEvent event) {
        UUID playerUuid = event.getUniqueId();
        String username = event.getName();

        UUID offlineUuid = plugin.getIdentityManager().getOrCreateOfflineUuid(username);
        String offlineUsername = plugin.getIdentityManager().getCanonicalName(
            com.valleyrealm.valleyauth.identity.IdentityType.OFFLINE, username);

        authStates.put(playerUuid, AuthState.OFFLINE);

        plugin.getLogger().info("Offline identity assigned: " + offlineUsername + " (" + offlineUuid + ")");
        event.allow();
    }

    /**
     * Mark a player as authenticated after successful verification.
     */
    public void setAuthenticated(UUID uuid, AuthState state) {
        authStates.put(uuid, state);
    }

    public AuthState getAuthState(UUID uuid) {
        return authStates.getOrDefault(uuid, AuthState.PENDING);
    }

    public boolean isAuthenticated(UUID uuid) {
        AuthState state = authStates.get(uuid);
        return state == AuthState.AUTHENTICATED || state == AuthState.PREMIUM || state == AuthState.BEDROCK;
    }

    public PasswordStore getPasswordStore() {
        return passwordStore;
    }

    public MojangSessionVerifier getSessionVerifier() {
        return sessionVerifier;
    }
}
