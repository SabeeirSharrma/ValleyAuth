package com.valleyrealm.valleyauth.cert;

import com.valleyrealm.valleyauth.ValleyAuth;
import com.valleyrealm.valleyauth.storage.StorageManager;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Enforces certificate authorization for protected Valley Auth operations.
 *
 * Per spec sections 48, 57, 62-65:
 * - Protected operations require valid certificate authorization.
 * - Invalid/missing/expired/revoked certificates result in denial.
 * - Plugins violating certificate requirements are marked UNSAFE.
 * - Unsafe status persists across server restarts.
 * - OPed players are notified on join.
 */
public class CertificateEnforcer {

    private final ValleyAuth plugin;
    private final StorageManager storageManager;

    /** plugin name -> is safe (true = has valid cert, false = invalid/missing) */
    private final Map<String, Boolean> pluginSafety = new ConcurrentHashMap<>();

    public CertificateEnforcer(ValleyAuth plugin) {
        this.plugin = plugin;
        this.storageManager = plugin.getStorageManager();
        loadUnsafePlugins();
    }

    /**
     * Validate that a plugin has a valid certificate for the given capability.
     *
     * @param pluginName the plugin requesting a protected operation
     * @param capability the required capability (e.g., "VLINK", "MIGRATION_PROVIDER")
     * @return true if the plugin has a valid certificate, false otherwise
     */
    public boolean validatePlugin(String pluginName, String capability) {
        if (pluginName == null || pluginName.isBlank()) {
            plugin.getLogger().warning("[ValleyAuth Enforcer] Null or empty plugin name.");
            return false;
        }

        CertificateValidator validator = plugin.getCertificateValidator();
        if (validator == null) {
            plugin.getLogger().warning("[ValleyAuth Enforcer] CertificateValidator not initialized — denying " + pluginName);
            markUnsafe(pluginName, "CertificateValidator not initialized");
            return false;
        }

        boolean valid = validator.isValid(pluginName, capability);

        if (valid) {
            pluginSafety.put(pluginName, true);
            plugin.getLogger().info("[ValleyAuth Enforcer] Plugin '" + pluginName + "' validated for capability '" + capability + "'.");
        } else {
            markUnsafe(pluginName, "Invalid or missing certificate for capability '" + capability + "'");
        }

        return valid;
    }

    public boolean isPluginSafe(String pluginName) {
        return Boolean.TRUE.equals(pluginSafety.get(pluginName));
    }

    /**
     * Mark a plugin as UNSAFE.
     *
     * @param pluginName the plugin to mark
     * @param reason     human-readable reason for the flag
     */
    public void markUnsafe(String pluginName, String reason) {
        pluginSafety.put(pluginName, false);
        storageManager.getUnsafePlugins().add(pluginName);
        storageManager.saveUnsafePlugins();

        plugin.getLogger().severe("[ValleyAuth Enforcer] Plugin '" + pluginName + "' marked UNSAFE.");
        plugin.getLogger().severe("[ValleyAuth Enforcer] Reason: " + reason);

        logSecurityEvent(pluginName, reason);
        notifyOperators(pluginName, reason);
    }

    public boolean hasUnsafePlugins() {
        return !storageManager.getUnsafePlugins().isEmpty();
    }

    public Set<String> getUnsafePlugins() {
        return Collections.unmodifiableSet(storageManager.getUnsafePlugins());
    }

    public int getUnsafePluginCount() {
        return storageManager.getUnsafePlugins().size();
    }

    public void loadUnsafePlugins() {
        Set<String> loaded = storageManager.loadUnsafePlugins();
        for (String name : loaded) {
            pluginSafety.put(name, false);
        }
    }

    public void saveUnsafePlugins() {
        storageManager.saveUnsafePlugins();
    }

    public void notifyOperators(String pluginName, String reason) {
        String message = String.format(
            "[ValleyAuth Enforcer] Security Warning: Plugin '%s' flagged UNSAFE. Reason: %s",
            pluginName, reason
        );

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.isOp()) {
                player.sendMessage("§c" + message);
            }
        }
    }

    public void notifyOperatorsOnJoin(Player player) {
        Set<String> unsafe = storageManager.getUnsafePlugins();
        if (unsafe.isEmpty()) return;
        if (!player.isOp()) return;

        player.sendMessage("§c[Valley Auth] Security Warning: " + unsafe.size() + " plugin(s) flagged UNSAFE. Check /valleyauth status for details.");
    }

    private void logSecurityEvent(String pluginName, String reason) {
        plugin.getLogger().log(Level.SEVERE,
            "[ValleyAuth Enforcer] SECURITY EVENT: Plugin '{0}' marked UNSAFE — {1}",
            new Object[]{pluginName, reason});
    }
}
