package com.valleyrealm.valleyauth.auth;

import com.valleyrealm.valleyauth.ValleyAuthPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent password storage for Offline Java identities.
 * Section 9 of the spec.
 *
 * Passwords are salted SHA-256 hashes stored in a YAML file.
 * The password belongs to the Offline identity — Premium and Bedrock
 * identities do not have Valley Auth passwords.
 */
public class PasswordStore {

    private static final String FILE_NAME = "offline-accounts.yml";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ValleyAuthPlugin plugin;
    private final File dataFile;
    private final Map<String, OfflineAccount> accounts = new ConcurrentHashMap<>();

    public PasswordStore(ValleyAuthPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), FILE_NAME);
        load();
    }

    /**
     * Register a new offline account.
     * @return true if successful, false if already registered
     */
    public boolean register(String username, String password) {
        String lowerName = username.toLowerCase();
        if (accounts.containsKey(lowerName)) {
            return false;
        }

        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        String hashedPassword = hashPassword(password, salt);

        OfflineAccount account = new OfflineAccount(username, hashedPassword, Base64.getEncoder().encodeToString(salt));
        accounts.put(lowerName, account);
        save();
        return true;
    }

    /**
     * Verify a password for an offline account.
     * @return true if password matches
     */
    public boolean verify(String username, String password) {
        OfflineAccount account = accounts.get(username.toLowerCase());
        if (account == null) {
            return false;
        }

        byte[] salt = Base64.getDecoder().decode(account.salt());
        String hashedPassword = hashPassword(password, salt);
        return hashedPassword.equals(account.hashedPassword());
    }

    /**
     * Check if an offline account is registered.
     */
    public boolean isRegistered(String username) {
        return accounts.containsKey(username.toLowerCase());
    }

    /**
     * Get the UUID for a registered offline account.
     */
    public UUID getUuid(String username) {
        OfflineAccount account = accounts.get(username.toLowerCase());
        return account != null ? UUID.fromString(account.uuid()) : null;
    }

    private String hashPassword(String password, byte[] salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private void load() {
        if (!dataFile.exists()) {
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        var section = config.getConfigurationSection("accounts");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            var accountSection = section.getConfigurationSection(key);
            if (accountSection == null) continue;

            String username = accountSection.getString("username", key);
            String hashedPassword = accountSection.getString("password", "");
            String salt = accountSection.getString("salt", "");
            String uuid = accountSection.getString("uuid", UUID.randomUUID().toString());

            accounts.put(key, new OfflineAccount(username, hashedPassword, salt));
        }

        plugin.getLogger().info("Loaded " + accounts.size() + " offline accounts.");
    }

    private void save() {
        YamlConfiguration config = new YamlConfiguration();

        for (Map.Entry<String, OfflineAccount> entry : accounts.entrySet()) {
            String path = "accounts." + entry.getKey();
            OfflineAccount account = entry.getValue();
            config.set(path + ".username", account.username());
            config.set(path + ".password", account.hashedPassword());
            config.set(path + ".salt", account.salt());
            config.set(path + ".uuid", account.uuid());
        }

        try {
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save offline accounts: " + e.getMessage());
        }
    }

    private record OfflineAccount(String username, String hashedPassword, String salt, String uuid) {
        OfflineAccount(String username, String hashedPassword, String salt) {
            this(username, hashedPassword, salt, UUID.randomUUID().toString());
        }
    }
}
