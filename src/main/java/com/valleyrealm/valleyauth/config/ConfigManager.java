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
    private String vlinkDomain = "";
    private boolean vlinkShowPort = false;
    private int vlinkCodeLength = 8;
    private int vlinkExpiryMinutes = 30;
    private boolean vlinkSslEnabled = false;
    private String vlinkSslKeystorePath = "keystore.jks";
    private String vlinkSslKeystorePassword = "";
    private String vlinkSslKeystoreType = "JKS";

    // Authentication settings
    private int loginTimeoutSeconds = 60;
    private int maxPasswordLength = 32;
    private int minPasswordLength = 6;

    // Security settings
    private boolean enforceCertificateAuthorization = true;
    private boolean logSecurityEvents = true;

    // Certificate settings
    private String certificateApiUrl = "https://cert.strawberry.dpdns.org";
    private String certificateDocsUrl = "https://docs.valleyrealm.qd.je/certificates";

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
        vlinkDomain = config.getString("vlink.domain", "");
        vlinkShowPort = config.getBoolean("vlink.show-port", false);
        vlinkCodeLength = config.getInt("vlink.code-length", 8);
        vlinkExpiryMinutes = config.getInt("vlink.expiry-minutes", 30);
        vlinkSslEnabled = config.getBoolean("vlink.ssl.enabled", false);
        vlinkSslKeystorePath = config.getString("vlink.ssl.keystore-path", "keystore.jks");
        vlinkSslKeystorePassword = config.getString("vlink.ssl.keystore-password", "");
        vlinkSslKeystoreType = config.getString("vlink.ssl.keystore-type", "JKS");

        // Authentication settings
        loginTimeoutSeconds = config.getInt("authentication.login-timeout-seconds", 60);
        maxPasswordLength = config.getInt("authentication.max-password-length", 32);
        minPasswordLength = config.getInt("authentication.min-password-length", 6);

        // Security settings
        enforceCertificateAuthorization = config.getBoolean("security.enforce-certificate-authorization", true);
        logSecurityEvents = config.getBoolean("security.log-security-events", true);

        // Certificate settings
        certificateApiUrl = config.getString("certificate.api-url", "https://cert.strawberry.dpdns.org");
        certificateDocsUrl = config.getString("certificate.docs-url", "https://docs.valleyrealm.qd.je/certificates");

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

    public String getVlinkDomain() { return vlinkDomain; }
    public boolean isVlinkShowPort() { return vlinkShowPort; }
    public boolean isVlinkSslEnabled() { return vlinkSslEnabled; }
    public String getVlinkSslKeystorePath() { return vlinkSslKeystorePath; }
    public String getVlinkSslKeystorePassword() { return vlinkSslKeystorePassword; }
    public String getVlinkSslKeystoreType() { return vlinkSslKeystoreType; }

    public int getLoginTimeoutSeconds() { return loginTimeoutSeconds; }
    public int getMaxPasswordLength() { return maxPasswordLength; }
    public int getMinPasswordLength() { return minPasswordLength; }

    public boolean isEnforceCertificateAuthorization() { return enforceCertificateAuthorization; }
    public boolean isLogSecurityEvents() { return logSecurityEvents; }
    public boolean isVerboseLogging() { return verboseLogging; }

    public String getCertificateApiUrl() { return certificateApiUrl; }
    public String getCertificateDocsUrl() { return certificateDocsUrl; }
}
