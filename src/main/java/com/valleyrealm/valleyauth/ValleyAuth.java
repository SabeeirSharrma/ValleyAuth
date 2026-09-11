package com.valleyrealm.valleyauth;

import com.valleyrealm.valleyauth.auth.AuthenticationManager;
import com.valleyrealm.valleyauth.auth.FloodgateAdapter;
import com.valleyrealm.valleyauth.listener.PlayerConnectionListener;
import com.valleyrealm.valleyauth.cert.ValleyCertClient;
import com.valleyrealm.valleyauth.config.ConfigManager;
import com.valleyrealm.valleyauth.identity.IdentityManager;
import com.valleyrealm.valleyauth.migration.MigrationManager;
import com.valleyrealm.valleyauth.security.UnsafeAddonManager;
import com.valleyrealm.valleyauth.storage.StorageManager;
import com.valleyrealm.valleyauth.vlink.VLinkManager;
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
    private FloodgateAdapter floodgateAdapter;
    private UnsafeAddonManager unsafeAddonManager;

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
        
        // Phase 2: ValleyCert initialization
        getLogger().info("[Valley Auth] Initializing certificate system...");
        valleyCertClient = new ValleyCertClient(this);
        valleyCertClient.initialize();
        
        // Phase 3: Core services (require valid cert)
        getLogger().info("[Valley Auth] Initializing authentication...");
        authenticationManager = new AuthenticationManager(this);
        
        getLogger().info("[Valley Auth] Initializing VLink...");
        vlinkManager = new VLinkManager(this);
        
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
        
        if (storageManager != null) {
            storageManager.shutdown();
        }
        
        getLogger().info("[Valley Auth] Disabled.");
    }

    private void registerCommands() {
        // Commands will be registered here
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
    public FloodgateAdapter getFloodgateAdapter() { return floodgateAdapter; }
    public UnsafeAddonManager getUnsafeAddonManager() { return unsafeAddonManager; }
}
