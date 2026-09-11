package com.valleyrealm.valleyauth.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.valleyrealm.valleyauth.ValleyAuth;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Valley Auth's persistent storage.
 * 
 * Stores:
 * - Offline UUID mappings
 * - Password hashes
 * - Migration job state
 * - VLink sessions
 * - Certificate data
 * - Unsafe addon status
 */
public class StorageManager {

    private final ValleyAuth plugin;
    private final Path dataFolder;
    private final Gson gson;
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final Map<String, String> offlineUuids = new ConcurrentHashMap<>();
    private final Map<String, String> passwordHashes = new ConcurrentHashMap<>();

    public StorageManager(ValleyAuth plugin) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataFolder().toPath();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public void initialize() {
        createDirectory(dataFolder);
        createDirectory(dataFolder.resolve("data"));
        createDirectory(dataFolder.resolve("migrations"));
        createDirectory(dataFolder.resolve("vlink"));
        createDirectory(dataFolder.resolve("certs"));

        loadOfflineUuids();
        loadPasswordHashes();

        plugin.getLogger().info("[Valley Auth] Storage initialized.");
    }

    public String getOrCreateOfflineUuid(String username, UuidGenerator generator) {
        String key = username.toLowerCase();
        String existing = offlineUuids.get(key);
        if (existing != null) return existing;

        String uuid = generator.generate(key);
        offlineUuids.put(key, uuid);
        saveOfflineUuids();
        return uuid;
    }

    public String getOfflineUuid(String username) {
        return offlineUuids.get(username.toLowerCase());
    }

    public void storePasswordHash(String uuid, String hash) {
        passwordHashes.put(uuid, hash);
        savePasswordHashes();
    }

    public String getPasswordHash(String uuid) {
        return passwordHashes.get(uuid);
    }

    public boolean hasPassword(String uuid) {
        return passwordHashes.containsKey(uuid);
    }

    public void shutdown() {
        saveOfflineUuids();
        savePasswordHashes();
        plugin.getLogger().info("[Valley Auth] Storage saved.");
    }

    private void createDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            plugin.getLogger().severe("[Valley Auth] Failed to create directory: " + path);
        }
    }

    private Path offlineUuidsFile() { return dataFolder.resolve("data").resolve("offline-uuids.json"); }
    private Path passwordsFile() { return dataFolder.resolve("data").resolve("passwords.json"); }

    private void loadOfflineUuids() {
        plugin.getLogger().info("[Valley Auth] Loading offline UUID mappings...");
        Path file = offlineUuidsFile();
        if (!Files.exists(file)) return;

        try {
            String json = Files.readString(file);
            Map<String, String> loaded = gson.fromJson(json, MAP_TYPE);
            if (loaded != null) offlineUuids.putAll(loaded);
        } catch (IOException e) {
            plugin.getLogger().warning("[Valley Auth] Failed to load offline UUIDs: " + e.getMessage());
        }
    }

    private void saveOfflineUuids() {
        try {
            Files.writeString(offlineUuidsFile(), gson.toJson(offlineUuids, MAP_TYPE));
        } catch (IOException e) {
            plugin.getLogger().warning("[Valley Auth] Failed to save offline UUIDs: " + e.getMessage());
        }
    }

    private void loadPasswordHashes() {
        plugin.getLogger().info("[Valley Auth] Loading password hashes...");
        Path file = passwordsFile();
        if (!Files.exists(file)) return;

        try {
            String json = Files.readString(file);
            Map<String, String> loaded = gson.fromJson(json, MAP_TYPE);
            if (loaded != null) passwordHashes.putAll(loaded);
        } catch (IOException e) {
            plugin.getLogger().warning("[Valley Auth] Failed to load password hashes: " + e.getMessage());
        }
    }

    private void savePasswordHashes() {
        try {
            Files.writeString(passwordsFile(), gson.toJson(passwordHashes, MAP_TYPE));
        } catch (IOException e) {
            plugin.getLogger().warning("[Valley Auth] Failed to save password hashes: " + e.getMessage());
        }
    }

    @FunctionalInterface
    public interface UuidGenerator {
        String generate(String key);
    }
}
