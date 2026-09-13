package com.valleyrealm.valleyauth;

import com.valleyrealm.valleyauth.auth.AuthManager;
import com.valleyrealm.valleyauth.auth.EnforcementManager;
import com.valleyrealm.valleyauth.auth.PacketBackend;
import com.valleyrealm.valleyauth.floodgate.FloodgateHook;
import com.valleyrealm.valleyauth.identity.IdentityManager;
import com.valleyrealm.valleyauth.command.RegisterCommand;
import com.valleyrealm.valleyauth.command.LoginCommand;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class ValleyAuthPlugin extends JavaPlugin {

    private boolean onlineMode;
    private boolean floodgateEnabled;
    private PacketBackend packetBackend;
    private AuthManager authManager;
    private IdentityManager identityManager;
    private EnforcementManager enforcementManager;

    @Override
    public void onEnable() {
        // Stage 79.1: Cache online-mode from server.properties
        this.onlineMode = Bukkit.getOnlineMode();
        getLogger().info("Server online-mode: " + onlineMode);

        // Stage 79.4 Step 2: Detect packet library
        this.packetBackend = detectPacketBackend();
        getLogger().info("Packet backend: " + packetBackend);

        // Stage 79.4 Step 0: Detect Floodgate
        this.floodgateEnabled = FloodgateHook.isAvailable();
        getLogger().info("Floodgate detected: " + floodgateEnabled);

        // Initialize managers
        this.identityManager = new IdentityManager(this);
        this.authManager = new AuthManager(this);

        // Register event listeners
        getServer().getPluginManager().registerEvents(authManager, this);

        // Register enforcement listener (offline-mode only — blocks unauthenticated Offline players)
        if (!onlineMode) {
            this.enforcementManager = new EnforcementManager(this);
            getServer().getPluginManager().registerEvents(enforcementManager, this);
        }

        // Stage 79.4: Register packet listener for forced Mojang verification (offline-mode only)
        if (!onlineMode) {
            authManager.registerPacketListener();
        }

        // Register commands
        getCommand("register").setExecutor(new RegisterCommand(this));
        getCommand("login").setExecutor(new LoginCommand(this));

        // Create data directory for persistent storage
        getDataFolder().mkdirs();

        getLogger().info("ValleyAuth enabled successfully.");
    }

    @Override
    public void onDisable() {
        identityManager.saveOfflineUuids();
        getLogger().info("ValleyAuth disabled.");
    }

    /**
     * Stage 79.4 Step 2: Detect which packet library is available.
     * Prefers packetevents over ProtocolLib. If neither is present,
     * forced auth is disabled (fail closed, not fail hang).
     */
    private PacketBackend detectPacketBackend() {
        boolean packetevents = Bukkit.getPluginManager().getPlugin("packetevents") != null;
        boolean protocolLib = Bukkit.getPluginManager().getPlugin("ProtocolLib") != null;

        if (packetevents && protocolLib) {
            getLogger().info("Both packetevents and ProtocolLib found — preferring packetevents");
            return PacketBackend.PACKETEVENTS;
        } else if (packetevents) {
            return PacketBackend.PACKETEVENTS;
        } else if (protocolLib) {
            return PacketBackend.PROTOCOL_LIB;
        } else {
            getLogger().warning("No packet library found — forced auth disabled (fail closed)");
            return PacketBackend.NONE;
        }
    }

    public boolean isOnlineMode() {
        return onlineMode;
    }

    public boolean isFloodgateEnabled() {
        return floodgateEnabled;
    }

    public PacketBackend getPacketBackend() {
        return packetBackend;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public IdentityManager getIdentityManager() {
        return identityManager;
    }

    public EnforcementManager getEnforcementManager() {
        return enforcementManager;
    }
}
