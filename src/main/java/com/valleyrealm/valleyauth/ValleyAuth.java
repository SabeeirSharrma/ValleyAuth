package com.valleyrealm.valleyauth;

import com.valleyrealm.valleyauth.auth.AuthenticationManager;
import com.valleyrealm.valleyauth.auth.FloodgateAdapter;
import com.valleyrealm.valleyauth.command.AuthCommandExecutor;
import com.valleyrealm.valleyauth.command.MigrateCommandExecutor;
import com.valleyrealm.valleyauth.command.ValleyAuthCommandExecutor;
import com.valleyrealm.valleyauth.listener.PlayerConnectionListener;
import com.valleyrealm.valleyauth.cert.ValleyCertClient;
import com.valleyrealm.valleyauth.cert.CertificateValidator;
import com.valleyrealm.valleyauth.cert.CertificateEnforcer;
import com.valleyrealm.valleyauth.config.ConfigManager;
import com.valleyrealm.valleyauth.identity.IdentityManager;
import com.valleyrealm.valleyauth.luckperms.LuckPermsAdapter;
import com.valleyrealm.valleyauth.migration.MigrationManager;
import com.valleyrealm.valleyauth.security.UnsafeAddonManager;
import com.valleyrealm.valleyauth.storage.StorageManager;
import com.valleyrealm.valleyauth.vlink.VLinkManager;
import com.valleyrealm.valleyauth.vlink.VLinkWebServer;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Valley Auth - Core Plugin
 * 
 * Authentication, identity, and player-data migration for Paper servers.
 * 
 * Certificate Flow:
 * Plugin init -> ValleyCert requests cert -> ValleyAuth Core gets cert from API
 * -> ValleyAuth gives to ValleyCert -> ValleyCert stores cert + sets expiry
 * -> Plugin validates cert -> continues init
 */
public class ValleyAuth extends JavaPlugin {

    private static ValleyAuth instance;
    
    private ConfigManager configManager;
    private IdentityManager identityManager;
    private AuthenticationManager authenticationManager;
    private VLinkManager vlinkManager;
    private MigrationManager migrationManager;
    private StorageManager storageManager;
    private ValleyCertClient valleyCertClient;
    private CertificateValidator certificateValidator;
    private CertificateEnforcer certificateEnforcer;
    private FloodgateAdapter floodgateAdapter;
    private LuckPermsAdapter luckPermsAdapter;
    private UnsafeAddonManager unsafeAddonManager;
    private VLinkWebServer webServer;

    @Override
    public void onEnable() {
        instance = this;
        
        // Phase 1: Core initialization (no cert required)
        getLogger().info("[Valley Auth] Initializing core systems...");
        
        configManager = new ConfigManager(this);
        configManager.loadConfig();
        
        storageManager = new StorageManager(this);
        storageManager.initialize();
        
        identityManager = new IdentityManager(this);

        // Phase 2: LuckPerms integration
        if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
            getLogger().info("[Valley Auth] Initializing LuckPerms adapter...");
            luckPermsAdapter = new LuckPermsAdapter(this);
            luckPermsAdapter.initialize();
        } else {
            getLogger().info("[Valley Auth] LuckPerms not found — permission migration disabled.");
        }
        
        // Phase 2: ValleyCert initialization
        getLogger().info("[Valley Auth] Initializing certificate system...");
        valleyCertClient = new ValleyCertClient(this);
        valleyCertClient.initialize();

        certificateValidator = new CertificateValidator(this);
        certificateEnforcer = new CertificateEnforcer(this);

        if (valleyCertClient.getCoreCertificate() != null) {
            String certId = valleyCertClient.getCoreCertificate().getCertificateId();
            getLogger().info("[Valley Auth] Core certificate: " + certId + " (CA: " + (valleyCertClient.isCaAvailable() ? "online" : "offline") + ")");
        } else {
            getLogger().warning("[Valley Auth] No core certificate available — running in offline mode.");
        }
        
        // Phase 3: Core services (require valid cert)
        getLogger().info("[Valley Auth] Initializing authentication...");
        authenticationManager = new AuthenticationManager(this);
        
        getLogger().info("[Valley Auth] Initializing VLink...");
        vlinkManager = new VLinkManager(this);

        if (configManager.isWebInterfaceEnabled()) {
            try {
                webServer = new VLinkWebServer(this);
                webServer.start();
                getLogger().info("[Valley Auth] Web interface started on port " + configManager.getWebInterfacePort());
            } catch (Exception e) {
                getLogger().warning("[Valley Auth] Failed to start web interface: " + e.getMessage());
            }
        }
        
        getLogger().info("[Valley Auth] Initializing migration system...");
        migrationManager = new MigrationManager(this);
        
        // Phase 4: Floodgate integration
        getLogger().info("[Valley Auth] Initializing Floodgate adapter...");
        floodgateAdapter = new FloodgateAdapter(this);

        // Phase 5: Security systems
        getLogger().info("[Valley Auth] Initializing unsafe addon detection...");
        unsafeAddonManager = new UnsafeAddonManager(this);

        // Register commands and listeners
        registerCommands();
        registerListeners();
        
        getLogger().info("[Valley Auth] " + getDescription().getVersion() + " enabled.");
        getLogger().info("[Valley Auth] " + identityManager.getStorageScanResult());
    }

    @Override
    public void onDisable() {
        getLogger().info("[Valley Auth] Shutting down...");
        
        if (migrationManager != null) {
            migrationManager.shutdown();
        }

        if (webServer != null) {
            webServer.close();
        }
        
        if (luckPermsAdapter != null) {
            luckPermsAdapter.shutdown();
        }

        if (storageManager != null) {
            storageManager.shutdown();
        }

        if (certificateEnforcer != null) {
            certificateEnforcer.saveUnsafePlugins();
        }
        
        getLogger().info("[Valley Auth] Disabled.");
    }

    private void registerCommands() {
        AuthCommandExecutor authCmd = new AuthCommandExecutor(this);
        getCommand("register").setExecutor(authCmd);
        getCommand("login").setExecutor(authCmd);
        getCommand("v").setExecutor(authCmd);

        ValleyAuthCommandExecutor vaCmd = new ValleyAuthCommandExecutor(this);
        getCommand("valleyauth").setExecutor(vaCmd);
        getCommand("valleyauth").setTabCompleter(vaCmd);

        MigrateCommandExecutor migrateCmd = new MigrateCommandExecutor(this);
        getCommand("migrate").setExecutor(migrateCmd);
        getCommand("migrate").setTabCompleter(migrateCmd);
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
            new PlayerConnectionListener(this), this);
    }

    public static ValleyAuth getInstance() {
        return instance;
    }

    // Getters for managers
    public ConfigManager getConfigManager() { return configManager; }
    public IdentityManager getIdentityManager() { return identityManager; }
    public AuthenticationManager getAuthenticationManager() { return authenticationManager; }
    public VLinkManager getVlinkManager() { return vlinkManager; }
    public MigrationManager getMigrationManager() { return migrationManager; }
    public StorageManager getStorageManager() { return storageManager; }
    public ValleyCertClient getValleyCertClient() { return valleyCertClient; }
    public CertificateValidator getCertificateValidator() { return certificateValidator; }
    public CertificateEnforcer getCertificateEnforcer() { return certificateEnforcer; }
    public FloodgateAdapter getFloodgateAdapter() { return floodgateAdapter; }
    public LuckPermsAdapter getLuckPermsAdapter() { return luckPermsAdapter; }
    public UnsafeAddonManager getUnsafeAddonManager() { return unsafeAddonManager; }
    public VLinkWebServer getWebServer() { return webServer; }
}
