package com.valleyrealm.valleyauth.identity;

import com.valleyrealm.valleyauth.ValleyAuthPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages identity creation and UUID persistence.
 * Sections 7-8 of the spec.
 *
 * Every identity is independently represented:
 *   Premium Java: Name → Mojang UUID
 *   Bedrock: .Name → Floodgate UUID
 *   Offline Java: -Name → ValleyAuth UUID
 *
 * The UUIDs remain independent unless an explicit supported identity-linking
 * system creates a relationship between them.
 *
 * Offline UUIDs must be persistent — every future Offline Java connection
 * using that identity must resolve to the same UUID.
 */
public class IdentityManager {

    private static final String FILE_NAME = "offline-uuids.yml";

    private final ValleyAuthPlugin plugin;
    private final IdentityPrefix prefix;
    private final File dataFile;

    /** username (bare, no prefix) → persistent Offline UUID */
    private final Map<String, UUID> offlineUuids = new ConcurrentHashMap<>();

    public IdentityManager(ValleyAuthPlugin plugin) {
        this.plugin = plugin;
        this.prefix = new IdentityPrefix();
        this.dataFile = new File(plugin.getDataFolder(), FILE_NAME);
        loadOfflineUuids();
    }

    /**
     * Get or create a persistent Offline UUID for a username.
     * Section 8: If -Sabeeir → UUID C, then every future Offline Java
     * connection using that identity must resolve to UUID C.
     */
    public UUID getOrCreateOfflineUuid(String username) {
        UUID existing = offlineUuids.get(username);
        if (existing != null) {
            return existing;
        }
        UUID newUuid = generateDeterministicUuid(username);
        offlineUuids.put(username, newUuid);
        saveOfflineUuids();
        return newUuid;
    }

    /**
     * Generate a deterministic UUID from a username.
     * Uses SHA-1 hash of a namespaced string to produce a v3-style UUID.
     * This ensures the same username always produces the same UUID,
     * even across server restarts.
     */
    private UUID generateDeterministicUuid(String username) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(("valleyauth:offline:" + username).getBytes(StandardCharsets.UTF_8));

            // Take first 16 bytes and set version/variant bits for v3 UUID
            byte[] uuidBytes = new byte[16];
            System.arraycopy(hash, 0, uuidBytes, 0, 16);

            // Version 3 (MD5 hash) — set bits 4-7 of byte 6
            uuidBytes[6] = (byte) ((uuidBytes[6] & 0x0F) | 0x30);
            // Variant 1 (RFC 4122) — set bits 6-7 of byte 8
            uuidBytes[8] = (byte) ((uuidBytes[8] & 0x3F) | 0x80);

            long mostSigBits = 0;
            for (int i = 0; i < 8; i++) {
                mostSigBits = (mostSigBits << 8) | (uuidBytes[i] & 0xFF);
            }
            long leastSigBits = 0;
            for (int i = 8; i < 16; i++) {
                leastSigBits = (leastSigBits << 8) | (uuidBytes[i] & 0xFF);
            }

            return new UUID(mostSigBits, leastSigBits);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }

    /**
     * Get the canonical username with prefix applied.
     * Example: getCanonicalName(OFFLINE, "Sabeeir") → "-Sabeeir"
     */
    public String getCanonicalName(IdentityType type, String username) {
        return prefix.applyPrefix(type, username);
    }

    /**
     * Get the bare username from a canonical name.
     * Example: getBareName("-Sabeeir") → "Sabeeir"
     */
    public String getBareName(String canonicalName) {
        return prefix.stripPrefix(canonicalName);
    }

    public IdentityPrefix getPrefixManager() {
        return prefix;
    }

    /**
     * Load offline UUIDs from persistent storage.
     */
    private void loadOfflineUuids() {
        if (!dataFile.exists()) {
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        var section = config.getConfigurationSection("accounts");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            String uuidStr = section.getString(key);
            if (uuidStr != null) {
                try {
                    offlineUuids.put(key, UUID.fromString(uuidStr));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid offline UUID for '" + key + "': " + uuidStr);
                }
            }
        }

        plugin.getLogger().info("Loaded " + offlineUuids.size() + " offline UUID mappings.");
    }

    public void saveOfflineUuids() {
        YamlConfiguration config = new YamlConfiguration();

        for (Map.Entry<String, UUID> entry : offlineUuids.entrySet()) {
            config.set("accounts." + entry.getKey(), entry.getValue().toString());
        }

        try {
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save offline UUIDs: " + e.getMessage());
        }
    }
}
