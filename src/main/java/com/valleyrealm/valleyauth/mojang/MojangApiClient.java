package com.valleyrealm.valleyauth.mojang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.valleyrealm.valleyauth.ValleyAuth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Mojang API client for Premium player verification.
 * 
 * Used to verify Premium Java players when the server is in offline-mode.
 * In online-mode, the server already verifies via session server.
 * 
 * Endpoints:
 * - Username lookup: https://api.mojang.com/users/profiles/minecraft/{username}
 * - Session verify: https://sessionserver.mojang.com/session/minecraft/hasJoined
 * 
 * Rate limits: ~60 requests per minute per IP.
 * Caching is used to avoid hitting rate limits.
 */
public class MojangApiClient {

    private final ValleyAuth plugin;
    private final HttpClient httpClient;
    private final boolean enabled;
    
    // Cache: username -> MojangProfile (avoids repeated API calls)
    private final Map<String, MojangProfile> profileCache = new ConcurrentHashMap<>();
    
    // Cache: username -> lookup result (exists/not exists) with TTL
    private final Map<String, CacheEntry<Boolean>> existenceCache = new ConcurrentHashMap<>();
    
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final long CACHE_TTL_MS = 30 * 60 * 1000; // 30 minutes
    private static final long NEGATIVE_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes for non-existent

    public MojangApiClient(ValleyAuth plugin, boolean enabled) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();
        
        if (enabled) {
            plugin.getLogger().info("[Valley Auth] Mojang API client enabled — Premium verification active.");
        } else {
            plugin.getLogger().info("[Valley Auth] Mojang API client disabled — Premium verification skipped.");
        }
    }

    /**
     * Check if a Mojang API client is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Check if a username exists on Mojang's servers.
     * Uses caching to avoid rate limits.
     * 
     * @param username The Minecraft username to check
     * @return true if the username exists on Mojang, false otherwise
     */
    public boolean isPremiumUsername(String username) {
        if (!enabled) return false;
        
        String key = username.toLowerCase();
        
        // Check cache first
        CacheEntry<Boolean> cached = existenceCache.get(key);
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }
        
        // Query Mojang API
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
                .header("Accept", "application/json")
                .GET()
                .timeout(HTTP_TIMEOUT)
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            boolean exists = response.statusCode() == 200 && response.body() != null && !response.body().isBlank();
            existenceCache.put(key, new CacheEntry<>(exists, exists ? CACHE_TTL_MS : NEGATIVE_CACHE_TTL_MS));
            
            if (exists) {
                plugin.getLogger().fine("[Valley Auth] Mojang: username '" + username + "' exists (Premium-reserved).");
            }
            
            return exists;
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Valley Auth] Mojang API lookup failed for '" + username + "': " + e.getMessage());
            // On error, don't cache — retry next time
            return false;
        }
    }

    /**
     * Async version of isPremiumUsername — non-blocking for use on Netty threads.
     *
     * @param username The Minecraft username to check
     * @return CompletableFuture that completes with true if the username exists on Mojang
     */
    public CompletableFuture<Boolean> isPremiumUsernameAsync(String username) {
        if (!enabled) return CompletableFuture.completedFuture(false);

        String key = username.toLowerCase();

        CacheEntry<Boolean> cached = existenceCache.get(key);
        if (cached != null && !cached.isExpired()) {
            return CompletableFuture.completedFuture(cached.value);
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
            .header("Accept", "application/json")
            .GET()
            .timeout(HTTP_TIMEOUT)
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                boolean exists = response.statusCode() == 200
                    && response.body() != null && !response.body().isBlank();
                existenceCache.put(key, new CacheEntry<>(exists, exists ? CACHE_TTL_MS : NEGATIVE_CACHE_TTL_MS));
                if (exists) {
                    plugin.getLogger().fine("[Valley Auth] Mojang: username '" + username + "' exists (Premium-reserved).");
                }
                return exists;
            })
            .exceptionally(ex -> {
                plugin.getLogger().log(Level.WARNING, "[Valley Auth] Mojang API lookup failed for '" + username + "': " + ex.getMessage());
                return false;
            });
    }

    /**
     * Get the Mojang UUID for a username.
     * Returns null if the username doesn't exist or the API call fails.
     * 
     * @param username The Minecraft username
     * @return The Mojang UUID, or null if not found
     */
    public UUID getMojangUuid(String username) {
        if (!enabled) return null;
        
        // Check if we have a cached profile
        MojangProfile cached = profileCache.get(username.toLowerCase());
        if (cached != null) {
            return cached.uuid();
        }
        
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + username))
                .header("Accept", "application/json")
                .GET()
                .timeout(HTTP_TIMEOUT)
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200 && response.body() != null && !response.body().isBlank()) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                
                String idStr = json.get("id").getAsString();
                String name = json.get("name").getAsString();
                
                // Mojang returns UUID without dashes
                UUID uuid = parseMojangUuid(idStr);
                
                MojangProfile profile = new MojangProfile(uuid, name);
                profileCache.put(username.toLowerCase(), profile);
                
                return uuid;
            }
            
            return null;
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Valley Auth] Mojang UUID lookup failed for '" + username + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Get the Mojang profile for a username.
     * Returns null if the username doesn't exist or the API call fails.
     * 
     * @param username The Minecraft username
     * @return The Mojang profile, or null if not found
     */
    public MojangProfile getMojangProfile(String username) {
        if (!enabled) return null;
        
        String key = username.toLowerCase();
        
        // Check cache first
        MojangProfile cached = profileCache.get(key);
        if (cached != null) {
            return cached;
        }
        
        // Query API
        UUID uuid = getMojangUuid(username);
        if (uuid != null) {
            return profileCache.get(key);
        }
        
        return null;
    }

    /**
     * Verify a Premium player using the Mojang session server.
     * This is the standard hasJoined verification used in online-mode.
     * 
     * @param username The player's username
     * @param serverId The server hash (SHA-1 of random + server IP)
     * @return The player's profile if verification succeeds, null otherwise
     */
    public MojangProfile verifySession(String username, String serverId) {
        if (!enabled) return null;
        
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/hasJoined?username=" + username + "&serverId=" + serverId))
                .header("Accept", "application/json")
                .GET()
                .timeout(HTTP_TIMEOUT)
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200 && response.body() != null && !response.body().isBlank()) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                
                String idStr = json.get("id").getAsString();
                String name = json.get("name").getAsString();
                
                UUID uuid = parseMojangUuid(idStr);
                MojangProfile profile = new MojangProfile(uuid, name);
                
                // Cache for future lookups
                profileCache.put(name.toLowerCase(), profile);
                
                plugin.getLogger().info("[Valley Auth] Mojang session verified: " + name + " (" + uuid + ")");
                
                return profile;
            }
            
            return null;
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Valley Auth] Mojang session verification failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Async version of verifySession — non-blocking for use on Netty threads.
     *
     * @param username The player's username
     * @param serverId The server hash (SHA-1 of shared secret + public key)
     * @return CompletableFuture that completes with the player's profile if verification succeeds, null otherwise
     */
    public CompletableFuture<MojangProfile> verifySessionAsync(String username, String serverId) {
        if (!enabled) return CompletableFuture.completedFuture(null);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/hasJoined?username=" + username + "&serverId=" + serverId))
            .header("Accept", "application/json")
            .GET()
            .timeout(HTTP_TIMEOUT)
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() == 200 && response.body() != null && !response.body().isBlank()) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    String idStr = json.get("id").getAsString();
                    String name = json.get("name").getAsString();
                    UUID uuid = parseMojangUuid(idStr);
                    MojangProfile profile = new MojangProfile(uuid, name);
                    profileCache.put(name.toLowerCase(), profile);
                    plugin.getLogger().info("[Valley Auth] Mojang session verified: " + name + " (" + uuid + ")");
                    return profile;
                }
                return null;
            })
            .exceptionally(ex -> {
                plugin.getLogger().log(Level.WARNING, "[Valley Auth] Mojang session verification failed: " + ex.getMessage());
                return null;
            });
    }

    /**
     * Parse a Mojang UUID (no dashes) into a standard UUID.
     */
    private UUID parseMojangUuid(String mojangId) {
        try {
            String dashed = mojangId.replaceFirst(
                "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                "$1-$2-$3-$4-$5"
            );
            return UUID.fromString(dashed);
        } catch (Exception e) {
            plugin.getLogger().warning("[Valley Auth] Failed to parse Mojang UUID: " + mojangId);
            return null;
        }
    }

    /**
     * Clear all caches (e.g., on config reload).
     */
    public void clearCache() {
        profileCache.clear();
        existenceCache.clear();
        plugin.getLogger().info("[Valley Auth] Mojang API cache cleared.");
    }

    /**
     * Shutdown the HTTP client.
     */
    public void shutdown() {
        clearCache();
        httpClient.close();
    }

    // Inner classes

    /**
     * Represents a Mojang player profile.
     */
    public record MojangProfile(UUID uuid, String username) {
        @Override
        public String toString() {
            return username + " (" + uuid + ")";
        }
    }

    /**
     * Cache entry with TTL.
     */
    private static class CacheEntry<T> {
        final T value;
        final long createdAt;
        final long ttlMs;

        CacheEntry(T value, long ttlMs) {
            this.value = value;
            this.createdAt = System.currentTimeMillis();
            this.ttlMs = ttlMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > ttlMs;
        }
    }
}
