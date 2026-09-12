package com.valleyrealm.valleyauth.identity;

import com.valleyrealm.valleyauth.ValleyAuth;
import com.valleyrealm.valleyauth.config.ConfigManager;
import com.valleyrealm.valleyauth.migration.MigrationIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class IdentityManager {

    private final ValleyAuth plugin;
    private final ConfigManager config;
    
    // Cache: canonicalName -> Identity
    private final Map<String, Identity> identityCache = new ConcurrentHashMap<>();
    
    // Cache: UUID -> canonicalName (for reverse lookup)
    private final Map<UUID, String> uuidToName = new ConcurrentHashMap<>();
    
    // Storage scan results
    private MigrationIndex cachedMigrationIndex;

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "json", "yml", "yaml", "properties", "txt", "conf", "cfg"
    );
    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
        "db", "sqlite", "sqlite3", "mdb"
    );

    private static final java.util.regex.Pattern USERNAME_PATTERN = 
        java.util.regex.Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    public IdentityManager(ValleyAuth plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        
        scanPluginStorage();
    }

    public boolean isValidUsername(String username) {
        return username != null && USERNAME_PATTERN.matcher(username).matches();
    }

    /**
     * Get or create an identity for a Premium Java player.
     * Uses the Mojang UUID directly.
     */
    public Identity getOrCreatePremium(String username, UUID mojangUuid) {
        if (!isValidUsername(username)) {
            plugin.getLogger().warning("[Valley Auth] Invalid premium username rejected: " + username);
            return null;
        }
        String prefix = config.getPremiumPrefix();
        String canonicalName = prefix + username;
        
        return identityCache.computeIfAbsent(canonicalName, 
            name -> new Identity(name, IdentityType.PREMIUM, mojangUuid));
    }

    /**
     * Get or create an identity for a Bedrock player.
     * Uses the Floodgate UUID.
     * Returns null if Floodgate/Bedrock is not available (soft dependency).
     */
    public Identity getOrCreateBedrock(String username, UUID floodgateUuid) {
        if (!isBedrockEnabled()) {
            plugin.getLogger().warning("[Valley Auth] Bedrock identity requested but Floodgate is not available.");
            return null;
        }
        if (!isValidUsername(username)) {
            plugin.getLogger().warning("[Valley Auth] Invalid bedrock username rejected: " + username);
            return null;
        }
        String prefix = config.getBedrockPrefix();
        String canonicalName = prefix + username;
        
        return identityCache.computeIfAbsent(canonicalName,
            name -> new Identity(name, IdentityType.BEDROCK, floodgateUuid));
    }

    /**
     * Get or create an identity for an Offline Java player.
     * Generates a persistent UUID if none exists.
     */
    public Identity getOrCreateOffline(String username) {
        if (!isValidUsername(username)) {
            plugin.getLogger().warning("[Valley Auth] Invalid offline username rejected: " + username);
            return null;
        }
        String prefix = config.getOfflinePrefix();
        String canonicalName = prefix + username;
        
        return identityCache.computeIfAbsent(canonicalName,
            name -> {
                // Load existing UUID from storage or generate new
                UUID offlineUuid = storageLoadOrCreateUuid(username);
                return new Identity(name, IdentityType.OFFLINE, offlineUuid);
            });
    }

    /**
     * Load existing offline UUID from storage or generate a new persistent one.
     */
    private UUID storageLoadOrCreateUuid(String username) {
        String stored = plugin.getStorageManager().getOfflineUuid(username);
        if (stored != null) {
            return UUID.fromString(stored);
        }
        UUID newUuid = UUID.nameUUIDFromBytes(("offline:" + username).getBytes());
        plugin.getStorageManager().getOrCreateOfflineUuid(username, key -> newUuid.toString());
        return newUuid;
    }

    /**
     * Get an identity by canonical name.
     */
    public Identity getIdentity(String canonicalName) {
        return identityCache.get(canonicalName);
    }

    /**
     * Get an identity by UUID.
     */
    public Identity getIdentityByUuid(UUID uuid) {
        String name = uuidToName.get(uuid);
        return name != null ? identityCache.get(name) : null;
    }

    /**
     * Check if a username is protected (has a Premium identity).
     * Used to prevent Offline impersonation.
     */
    public boolean isUsernameProtected(String username) {
        // Check if any Premium identity exists with this username
        return identityCache.keySet().stream()
            .filter(name -> name.endsWith(username))
            .map(identityCache::get)
            .anyMatch(id -> id.getType() == IdentityType.PREMIUM);
    }

    /**
     * Check if Bedrock/Floodgate is enabled on this server.
     */
    public boolean isBedrockEnabled() {
        return plugin.getFloodgateAdapter() != null && plugin.getFloodgateAdapter().isAvailable();
    }

    /**
     * Validate that an identity operation is allowed by the security matrix.
     * When Bedrock branch is disabled, OFFLINE→BEDROCK is not allowed.
     */
    public boolean isMigrationAllowed(IdentityType source, IdentityType destination) {
        if (source == IdentityType.OFFLINE && destination == IdentityType.PREMIUM) return true;
        if (source == IdentityType.OFFLINE && destination == IdentityType.BEDROCK) {
            return isBedrockEnabled();
        }
        
        return false;
    }

    /**
     * STARTUP PHASE: Index candidate file paths + format only.
     * No file contents are opened or read. Cache the index for later use.
     */
    private void scanPluginStorage() {
        plugin.getLogger().info("[Valley Auth] Indexing candidate migration files...");

        List<MigrationIndex.IndexedFile> indexed = new ArrayList<>();
        int totalCount = 0;
        int supportedCount = 0;
        int excludedCount = 0;

        Path pluginsDir = plugin.getServer().getPluginsFolder().toPath();
        try (java.util.stream.Stream<Path> paths = Files.walk(pluginsDir)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!Files.isRegularFile(path)) continue;
                totalCount++;

                String fileName = path.getFileName().toString().toLowerCase();
                if (EXCLUDED_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
                    excludedCount++;
                    indexed.add(new MigrationIndex.IndexedFile(path, getFormat(fileName), false));
                } else if (SUPPORTED_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
                    supportedCount++;
                    indexed.add(new MigrationIndex.IndexedFile(path, getFormat(fileName), true));
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[Valley Auth] Error indexing plugin storage: " + e.getMessage());
        }

        cachedMigrationIndex = new MigrationIndex(indexed, totalCount, supportedCount, excludedCount);
        plugin.getLogger().info("[Valley Auth] " + cachedMigrationIndex.toSummaryString());
    }

    private String getFormat(String fileName) {
        if (fileName.endsWith(".json")) return "json";
        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) return "yaml";
        if (fileName.endsWith(".properties")) return "properties";
        if (fileName.endsWith(".txt")) return "text";
        if (fileName.endsWith(".conf") || fileName.endsWith(".cfg")) return "config";
        return "unknown";
    }

    /**
     * Get the cached migration index. Returns null if scan hasn't run yet.
     */
    public MigrationIndex getCachedMigrationIndex() {
        return cachedMigrationIndex;
    }

    /**
     * Get storage scan result summary.
     */
    public String getStorageScanResult() {
        if (cachedMigrationIndex == null) return "Migration index not yet built.";
        return cachedMigrationIndex.toSummaryString();
    }
}
