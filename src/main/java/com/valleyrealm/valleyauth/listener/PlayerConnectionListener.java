package com.valleyrealm.valleyauth.listener;

import com.valleyrealm.valleyauth.ValleyAuth;
import com.valleyrealm.valleyauth.auth.AuthenticationManager;
import com.valleyrealm.valleyauth.auth.FloodgateAdapter;
import com.valleyrealm.valleyauth.auth.MojangSessionVerifier;
import com.valleyrealm.valleyauth.identity.Identity;
import com.valleyrealm.valleyauth.identity.IdentityManager;
import com.valleyrealm.valleyauth.identity.IdentityType;
import com.valleyrealm.valleyauth.migration.MigrationJob;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class PlayerConnectionListener implements Listener {

    private final ValleyAuth plugin;
    private final AuthenticationManager authManager;
    private final IdentityManager identityManager;
    private final FloodgateAdapter floodgateAdapter;
    private final Map<UUID, BukkitTask> authTimers = new HashMap<>();
    private final Map<UUID, Location> joinPositions = new HashMap<>();

    public PlayerConnectionListener(ValleyAuth plugin) {
        this.plugin = plugin;
        this.authManager = plugin.getAuthenticationManager();
        this.identityManager = plugin.getIdentityManager();
        this.floodgateAdapter = plugin.getFloodgateAdapter();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String username = player.getName();
        boolean isOnlineMode = player.getServer().getOnlineMode();

        plugin.getUnsafeAddonManager().notifyOperatorsOnJoin(player);

        if (plugin.getCertificateEnforcer() != null) {
            plugin.getCertificateEnforcer().notifyOperatorsOnJoin(player);
        }

        try {
            MojangSessionVerifier sessionVerifier = plugin.getMojangSessionVerifier();
            IdentityType identityType;
            UUID resolvedUuid = playerUuid;

            if (sessionVerifier != null && sessionVerifier.isEnabled() && !isOnlineMode) {
                if (sessionVerifier.isSessionVerified(playerUuid)) {
                    identityType = IdentityType.PREMIUM;
                    resolvedUuid = sessionVerifier.getVerifiedMojangUuid(playerUuid);
                    plugin.getLogger().info("[Valley Auth] " + username + " verified via Mojang session — Premium.");
                } else {
                    identityType = IdentityType.OFFLINE;
                    plugin.getLogger().info("[Valley Auth] " + username + " failed Mojang session — Offline.");
                }
            } else {
                identityType = floodgateAdapter.resolveIdentityType(playerUuid, username, isOnlineMode);
            }

            Identity identity = getOrCreateIdentity(username, resolvedUuid, identityType);
            AuthenticationManager.AuthResult authResult = authManager.onPlayerJoin(username, resolvedUuid, identityType);

            joinPositions.put(playerUuid, player.getLocation());

            if (authResult.isSuccess()) {
                if (identityType == IdentityType.PREMIUM && !isOnlineMode) {
                    player.sendMessage("§a[Valley Auth] Premium account verified via Mojang — automatic authentication.");
                } else {
                    player.sendMessage("§a[Valley Auth] " + authResult.getMessage());
                }
                cancelAuthTimer(playerUuid);
            } else if (authResult.isRequiresRegistration()) {
                player.sendMessage("§e[Valley Auth] " + authResult.getMessage());
                startAuthTimer(player);
            } else if (authResult.isRequiresLogin()) {
                player.sendMessage("§e[Valley Auth] " + authResult.getMessage());
                startAuthTimer(player);
            } else {
                player.sendMessage("§c[Valley Auth] " + authResult.getMessage());
                player.kickPlayer("§cAuthentication failed: " + authResult.getMessage());
            }

            checkPendingMigration(player, identity);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[Valley Auth] Error handling join for " + username, e);
            player.kickPlayer("§cInternal error during authentication.");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        authManager.onPlayerDisconnect(playerUuid);
        cancelAuthTimer(playerUuid);
        joinPositions.remove(playerUuid);

        MojangSessionVerifier sessionVerifier = plugin.getMojangSessionVerifier();
        if (sessionVerifier != null) {
            sessionVerifier.cleanupSession(playerUuid);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!authManager.isAuthenticated(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage("§c[Valley Auth] You must be authenticated to chat.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();

        if (authManager.isAuthenticated(playerUuid)) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        if (from.distanceSquared(to) > 0.25) {
            event.setCancelled(true);
        }
    }

    private void startAuthTimer(Player player) {
        UUID playerUuid = player.getUniqueId();
        cancelAuthTimer(playerUuid);

        int[] remainingSeconds = { plugin.getConfigManager().getLoginTimeoutSeconds() };
        authTimers.put(playerUuid, plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (authManager.isAuthenticated(playerUuid)) {
                cancelAuthTimer(playerUuid);
                return;
            }

            Player target = plugin.getServer().getPlayer(playerUuid);
            if (target == null || !target.isOnline()) {
                cancelAuthTimer(playerUuid);
                return;
            }

            remainingSeconds[0]--;
            if (remainingSeconds[0] <= 0) {
                target.kickPlayer("§c[Valley Auth] Authentication timed out.");
                cancelAuthTimer(playerUuid);
            }
        }, 20L, 20L));
    }

    private void cancelAuthTimer(UUID playerUuid) {
        BukkitTask task = authTimers.remove(playerUuid);
        if (task != null) {
            task.cancel();
        }
    }

    private Identity getOrCreateIdentity(String username, UUID playerUuid, IdentityType type) {
        switch (type) {
            case PREMIUM:
                return identityManager.getOrCreatePremium(username, playerUuid);
            case BEDROCK:
                Identity bedrock = identityManager.getOrCreateBedrock(username, playerUuid);
                if (bedrock == null) {
                    plugin.getLogger().warning("[Valley Auth] Bedrock identity creation failed — Floodgate unavailable. Falling back to Offline.");
                    return identityManager.getOrCreateOffline(username);
                }
                return bedrock;
            case OFFLINE:
                return identityManager.getOrCreateOffline(username);
            default:
                throw new IllegalArgumentException("Unknown identity type: " + type);
        }
    }

    private void checkPendingMigration(Player player, Identity identity) {
        MigrationJob pendingJob = plugin.getMigrationManager().getPendingJobForSource(identity.getUuid());
        if (pendingJob != null) {
            player.sendMessage("§6[Valley Auth] You have a pending migration.");
            player.sendMessage("§6Use /v status to check progress.");
        }
    }
}
