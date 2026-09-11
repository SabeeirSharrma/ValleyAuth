package com.valleyrealm.valleyauth.config;

import com.valleyrealm.valleyauth.ValleyAuth;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Manages Valley Auth configuration.
 * 
 * Provides sensible defaults while allowing advanced customization.
 */
public class ConfigManager {

    private final ValleyAuth plugin;
    private FileConfiguration config;

    // Identity prefixes
    private String premiumPrefix = "";
    private String bedrockPrefix = ".";
    private String offlinePrefix = "-";

    // Migration settings
    private int maxConcurrentMigrations = 5;
    private boolean webInterfaceEnabled = true;
    private int webInterfacePort = 8080;

    // VLink settings
    private int vlinkCodeLength = 8;
    private int vlinkExpiryMinutes = 30;

    // Authentication settings
    private int loginTimeoutSeconds = 60;
    private int maxPasswordLength = 32;
    private int minPasswordLength = 6;

    // Security settings
    private boolean enforceCertificateAuthorization = true;
    private boolean logSecurityEvents = true;

    // Logging
    private boolean verboseLogging = false;

    public ConfigManager(ValleyAuth plugin) {
        this.plugin = plugin;
    }

    /**
     * Load configuration from config.yml.
     * Creates defaults if file doesn't exist.
     */
    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();

        // Identity prefixes
        premiumPrefix = config.getString("identity.premium-prefix", "");
        bedrockPrefix = config.getString("identity.bedrock-prefix", ".");
        offlinePrefix = config.getString("identity.offline-prefix", "-");

        // Migration settings
        maxConcurrentMigrations = config.getInt("migration.max-concurrent", 5);
        webInterfaceEnabled = config.getBoolean("migration.web-interface.enabled", true);
        webInterfacePort = config.getInt("migration.web-interface.port", 8080);

        // VLink settings
        vlinkCodeLength = config.getInt("vlink.code-length", 8);
        vlinkExpiryMinutes = config.getInt("vlink.expiry-minutes", 30);

        // Authentication settings
        loginTimeoutSeconds = config.getInt("auth.login-timeout-seconds", 60);
        maxPasswordLength = config.getInt("auth.max-password-length", 32);
        minPasswordLength = config.getInt("auth.min-password-length", 6);

        // Security settings
        enforceCertificateAuthorization = config.getBoolean("security.enforce-certificate-authorization", true);
        logSecurityEvents = config.getBoolean("security.log-security-events", true);

        // Logging
        verboseLogging = config.getBoolean("logging.verbose", false);

        plugin.getLogger().info("[Valley Auth] Configuration loaded.");
    }

    // Getters
    public String getPremiumPrefix() { return premiumPrefix; }
    public String getBedrockPrefix() { return bedrockPrefix; }
    public String getOfflinePrefix() { return offlinePrefix; }

    public int getMaxConcurrentMigrations() { return maxConcurrentMigrations; }
    public boolean isWebInterfaceEnabled() { return webInterfaceEnabled; }
    public int getWebInterfacePort() { return webInterfacePort; }

    public int getVlinkCodeLength() { return vlinkCodeLength; }
    public int getVlinkExpiryMinutes() { return vlinkExpiryMinutes; }

    public int getLoginTimeoutSeconds() { return loginTimeoutSeconds; }
    public int getMaxPasswordLength() { return maxPasswordLength; }
    public int getMinPasswordLength() { return minPasswordLength; }

    public boolean isEnforceCertificateAuthorization() { return enforceCertificateAuthorization; }
    public boolean isLogSecurityEvents() { return logSecurityEvents; }
    public boolean isVerboseLogging() { return verboseLogging; }
}
