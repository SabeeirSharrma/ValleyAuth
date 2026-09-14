package com.valleyrealm.valleyauth.floodgate;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Floodgate integration for Bedrock player detection.
 * Section 3.2 of the spec — Floodgate is a soft dependency.
 *
 * Floodgate handles:
 * - Adding the '.' prefix to Bedrock usernames
 * - Assigning Floodgate UUIDs
 * - Bedrock authentication
 *
 * Valley Auth just detects Floodgate connections and routes them.
 */
public final class FloodgateHook {

    private static Boolean available;

    private FloodgateHook() {}

    /**
     * Check if Floodgate is installed and available.
     * Result is cached on first call.
     */
    public static boolean isAvailable() {
        if (available == null) {
            available = Bukkit.getPluginManager().getPlugin("floodgate") != null;
        }
        return available;
    }

    /**
     * Check if the given UUID belongs to a Bedrock player via Floodgate.
     * Works even in pre-login events (player doesn't need to be fully online).
     * Returns false if Floodgate is not installed.
     *
     * Section 79.4 Step 0: This is the first check in offline-mode path.
     */
    public static boolean isBedrockPlayer(UUID uuid) {
        if (!isAvailable()) return false;
        try {
            return org.geysermc.floodgate.api.FloodgateApi.getInstance().isFloodgatePlayer(uuid);
        } catch (NoClassDefFoundError | Exception e) {
            return false;
        }
    }

    /**
     * Check Floodgate's connected player list for a matching username.
     * Geyser may not include the Floodgate UUID in LOGIN_START,
     * so we also check by iterating Floodgate's player map.
     */
    public static boolean isBedrockPlayerByName(String username) {
        if (!isAvailable()) return false;
        try {
            var players = org.geysermc.floodgate.api.FloodgateApi.getInstance().getPlayers();
            for (var player : players) {
                String javaUsername = player.getJavaUsername();
                if (javaUsername != null && (javaUsername.equals(username) || javaUsername.equals("." + username))) {
                    return true;
                }
            }
        } catch (NoClassDefFoundError | Exception e) {
            return false;
        }
        return false;
    }

    /**
     * Check if the given player is a Bedrock player.
     */
    public static boolean isBedrockPlayer(Player player) {
        return isBedrockPlayer(player.getUniqueId());
    }

    /**
     * Get the Floodgate player object.
     * Returns null if Floodgate is not installed or player is not Bedrock.
     */
    public static org.geysermc.floodgate.api.player.FloodgatePlayer getFloodgatePlayer(UUID uuid) {
        if (!isAvailable()) return null;
        try {
            return org.geysermc.floodgate.api.FloodgateApi.getInstance().getPlayer(uuid);
        } catch (NoClassDefFoundError | Exception e) {
            return null;
        }
    }

    /**
     * Get the correct UUID for a Bedrock player.
     * For unlinked accounts: Floodgate UUID (from XUID)
     * For linked accounts: Java UUID
     */
    public static UUID getCorrectUuid(UUID playerUuid) {
        org.geysermc.floodgate.api.player.FloodgatePlayer fp = getFloodgatePlayer(playerUuid);
        return fp != null ? fp.getCorrectUniqueId() : null;
    }

    /**
     * Check if Geyser is present (Bedrock protocol translator).
     */
    public static boolean isGeyserPresent() {
        return Bukkit.getPluginManager().getPlugin("Geyser-Spigot") != null;
    }
}
