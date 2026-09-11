package com.valleyrealm.valleyauth.auth;

import com.valleyrealm.valleyauth.ValleyAuth;
import com.valleyrealm.valleyauth.identity.IdentityType;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Adapter for Floodgate Bedrock integration.
 * 
 * Floodgate is the core dependency for Bedrock support.
 * This adapter handles:
 * - Detecting Floodgate presence
 * - Identifying Bedrock players
 * - Extracting Floodgate UUIDs
 * - Mapping Bedrock identities
 * 
 * Uses reflection to avoid hard compile-time dependency.
 * Floodgate is a soft dependency - plugin works without it.
 */
public class FloodgateAdapter {

    private final ValleyAuth plugin;
    private boolean floodgateAvailable = false;
    private Object floodgateApi = null;

    // Reflection cached methods
    private Method isFloodgatePlayerMethod = null;
    private Method getFloodgatePlayerMethod = null;
    private Method getUuidMethod = null;
    private Method getCorrectUsernameMethod = null;

    public FloodgateAdapter(ValleyAuth plugin) {
        this.plugin = plugin;
        detectFloodgate();
    }

    /**
     * Detect if Floodgate is available on the server.
     */
    private void detectFloodgate() {
        try {
            Object geyserPlugin = plugin.getServer().getPluginManager().getPlugin("Geyser-Spigot");
            if (geyserPlugin == null) {
                geyserPlugin = plugin.getServer().getPluginManager().getPlugin("Geyser");
            }
            if (geyserPlugin == null) {
                plugin.getLogger().info("[Valley Auth] Geyser not found — Bedrock support disabled.");
                return;
            }

            Class<?> floodgatePluginClass = Class.forName("org.geysermc.floodgate.FloodgatePlugin");
            Object floodgatePlugin = plugin.getServer().getPluginManager().getPlugin("floodgate");

            if (floodgatePlugin == null) {
                plugin.getLogger().warning("[Valley Auth] Geyser found but Floodgate is not installed.");
                plugin.getLogger().warning("[Valley Auth] Download Floodgate from https://ci.opencollab.dev/job/GeyserMC/job/Floodgate/job/master/ to enable Bedrock support.");
                return;
            }

            // Get API instance
            Method getApiMethod = floodgatePluginClass.getMethod("getApi");
            floodgateApi = getApiMethod.invoke(floodgatePlugin);
            
            if (floodgateApi == null) {
                plugin.getLogger().warning("[Valley Auth] Floodgate API unavailable.");
                return;
            }

            // Cache reflection methods
            Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            isFloodgatePlayerMethod = floodgateApiClass.getMethod("isFloodgatePlayer", UUID.class);
            getFloodgatePlayerMethod = floodgateApiClass.getMethod("getPlayer", UUID.class);
            
            Class<?> floodgatePlayerClass = Class.forName("org.geysermc.floodgate.api.player.FloodgatePlayer");
            getUuidMethod = floodgatePlayerClass.getMethod("getUuid");
            getCorrectUsernameMethod = floodgatePlayerClass.getMethod("getCorrectUsername");

            floodgateAvailable = true;
            plugin.getLogger().info("[Valley Auth] Geyser + Floodgate detected — Bedrock support enabled.");

        } catch (ClassNotFoundException e) {
            plugin.getLogger().info("[Valley Auth] Floodgate classes not found — Bedrock support disabled.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Valley Auth] Failed to initialize Floodgate adapter", e);
        }
    }

    /**
     * Check if Floodgate is available.
     */
    public boolean isAvailable() {
        return floodgateAvailable;
    }

    /**
     * Check if a player is a Floodgate (Bedrock) player.
     */
    public boolean isBedrockPlayer(UUID playerUuid) {
        if (!floodgateAvailable) {
            return false;
        }

        try {
            return (Boolean) isFloodgatePlayerMethod.invoke(floodgateApi, playerUuid);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Valley Auth] Error checking Floodgate player", e);
            return false;
        }
    }

    /**
     * Get the Floodgate UUID for a Bedrock player.
     * Returns null if not a Bedrock player.
     */
    public UUID getBedrockUuid(UUID playerUuid) {
        if (!isBedrockPlayer(playerUuid)) {
            return null;
        }

        try {
            Object floodgatePlayer = getFloodgatePlayerMethod.invoke(floodgateApi, playerUuid);
            if (floodgatePlayer != null) {
                return (UUID) getUuidMethod.invoke(floodgatePlayer);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Valley Auth] Error getting Bedrock UUID", e);
        }

        return null;
    }

    /**
     * Get the correct username for a Bedrock player.
     * Bedrock usernames may differ from Java display names.
     */
    public String getBedrockUsername(UUID playerUuid) {
        if (!isBedrockPlayer(playerUuid)) {
            return null;
        }

        try {
            Object floodgatePlayer = getFloodgatePlayerMethod.invoke(floodgateApi, playerUuid);
            if (floodgatePlayer != null) {
                return (String) getCorrectUsernameMethod.invoke(floodgatePlayer);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Valley Auth] Error getting Bedrock username", e);
        }

        return null;
    }

    /**
     * Determine identity type from player connection.
     */
    public IdentityType resolveIdentityType(UUID playerUuid, boolean isOnlineMode) {
        // Check Floodgate first
        if (floodgateAvailable && isBedrockPlayer(playerUuid)) {
            return IdentityType.BEDROCK;
        }

        // Premium if online mode
        if (isOnlineMode) {
            return IdentityType.PREMIUM;
        }

        // Otherwise offline
        return IdentityType.OFFLINE;
    }
}
