package com.valleyrealm.valleyauth.security;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.valleyrealm.valleyauth.ValleyAuth;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects and manages addons/plugins that violate certificate requirements.
 *
 * Security invariant: if a plugin has been authorized for protected Valley Auth
 * functionality but attempts that functionality without presenting its required
 * certificate, Valley Auth must treat this as a security violation.
 *
 * Violations persist across server restarts.
 */
public class UnsafeAddonManager {

    private final ValleyAuth plugin;
    private final Gson gson;
    private final Path persistenceFile;

    private final Map<String, UnsafeEntry> flaggedAddons = new ConcurrentHashMap<>();
    private final Set<String> notifiedOperators = ConcurrentHashMap.newKeySet();

    public UnsafeAddonManager(ValleyAuth plugin) {
        this.plugin = plugin;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.persistenceFile = plugin.getDataFolder().toPath().resolve("data").resolve("unsafe-addons.json");
        loadPersistedStatuses();
    }

    /**
     * Report an addon for performing a protected operation without a valid certificate.
     *
     * @param addonName The plugin/addon name
     * @param operation The protected operation attempted (e.g., "VLINK", "MIGRATION_PROVIDER")
     * @param reason Detailed reason for the violation
     */
    public void reportViolation(String addonName, String operation, String reason) {
        UnsafeEntry entry = new UnsafeEntry(
            addonName,
            operation,
            reason,
            System.currentTimeMillis()
        );

        flaggedAddons.put(addonName, entry);
        persistStatuses();

        plugin.getLogger().severe("[Valley Auth] WARNING");
        plugin.getLogger().severe("Addon '" + addonName + "' has been flagged UNSAFE.");
        plugin.getLogger().severe("Reason: " + reason);
        plugin.getLogger().severe("Operation blocked: " + operation);

        notifyOperators(addonName, operation, reason);
    }

    /**
     * Check if an addon has been flagged as unsafe.
     */
    public boolean isUnsafe(String addonName) {
        return flaggedAddons.containsKey(addonName);
    }

    /**
     * Get all flagged addons.
     */
    public Map<String, UnsafeEntry> getFlaggedAddons() {
        return Collections.unmodifiableMap(flaggedAddons);
    }

    /**
     * Get the count of flagged addons.
     */
    public int getFlaggedCount() {
        return flaggedAddons.size();
    }

    /**
     * Resolve an unsafe status (administrative action).
     */
    public boolean resolveStatus(String addonName) {
        if (flaggedAddons.remove(addonName) != null) {
            persistStatuses();
            plugin.getLogger().info("[Valley Auth] Unsafe status resolved for: " + addonName);
            return true;
        }
        return false;
    }

    /**
     * Notify OPed players of security violations on join.
     */
    public void notifyOperatorsOnJoin(Player player) {
        if (flaggedAddons.isEmpty()) return;
        if (!player.isOp()) return;

        String key = player.getUniqueId() + ":" + flaggedAddons.hashCode();
        if (notifiedOperators.contains(key)) return;
        notifiedOperators.add(key);

        player.sendMessage("§c[Valley Auth] Security Warning");
        player.sendMessage("§c" + flaggedAddons.size() + " addon(s) flagged UNSAFE:");
        for (Map.Entry<String, UnsafeEntry> entry : flaggedAddons.entrySet()) {
            player.sendMessage("§c - " + entry.getKey() + ": " + entry.getValue().operation());
        }
        player.sendMessage("§cCheck server logs for details.");
    }

    private void notifyOperators(String addonName, String operation, String reason) {
        String message = String.format(
            "[Valley Auth] Security Warning\nAddon '%s' flagged UNSAFE.\nReason: %s\nOperation: %s",
            addonName, reason, operation
        );

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.isOp()) {
                player.sendMessage("§c" + message);
            }
        }
    }

    private void loadPersistedStatuses() {
        if (!Files.exists(persistenceFile)) return;

        try {
            String json = Files.readString(persistenceFile);
            Type listType = new TypeToken<List<UnsafeEntry>>() {}.getType();
            List<UnsafeEntry> entries = gson.fromJson(json, listType);
            if (entries != null) {
                for (UnsafeEntry entry : entries) {
                    flaggedAddons.put(entry.addonName(), entry);
                }
            }
            plugin.getLogger().info("[Valley Auth] Loaded " + flaggedAddons.size() + " unsafe addon status(es).");
        } catch (IOException e) {
            plugin.getLogger().warning("[Valley Auth] Failed to load unsafe addon statuses: " + e.getMessage());
        }
    }

    private void persistStatuses() {
        try {
            Files.createDirectories(persistenceFile.getParent());
            List<UnsafeEntry> entries = new ArrayList<>(flaggedAddons.values());
            Files.writeString(persistenceFile, gson.toJson(entries));
        } catch (IOException e) {
            plugin.getLogger().warning("[Valley Auth] Failed to persist unsafe addon statuses: " + e.getMessage());
        }
    }

    public record UnsafeEntry(
        String addonName,
        String operation,
        String reason,
        long timestamp
    ) {}
}
