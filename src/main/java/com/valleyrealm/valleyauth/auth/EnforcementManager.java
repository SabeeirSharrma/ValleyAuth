package com.valleyrealm.valleyauth.auth;

import com.valleyrealm.valleyauth.ValleyAuthPlugin;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Set;
import java.util.UUID;

public class EnforcementManager implements Listener {

    private static final String TITLE = ChatColor.RED + "Authentication Required";
    private static final String SUBTITLE = ChatColor.GRAY + "Type " + ChatColor.WHITE + "/register <password>" + ChatColor.GRAY + " or " + ChatColor.WHITE + "/login <password>";

    private static final Set<String> ALLOWED_COMMANDS = Set.of("/register", "/login");

    private final ValleyAuthPlugin plugin;

    public EnforcementManager(ValleyAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String username = player.getName();

        plugin.getLogger().info("EnforcementManager onPlayerJoin: " + username + " uuid=" + uuid + " authenticated=" + plugin.getAuthManager().isAuthenticated(uuid));

        if (!needsEnforcement(uuid)) {
            return;
        }

        player.sendTitle(TITLE, SUBTITLE, 10, 60, 20);
        player.sendMessage(ChatColor.RED + "Please " + ChatColor.WHITE + "/register <password>" + ChatColor.RED + " or " + ChatColor.WHITE + "/login <password>" + ChatColor.RED + " to play.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!needsEnforcement(event.getPlayer().getUniqueId())) {
            return;
        }

        if (hasMovedBlock(event)) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(PlayerChatEvent event) {
        if (!needsEnforcement(event.getPlayer().getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.RED + "Please " + ChatColor.WHITE + "/register" + ChatColor.RED + " or " + ChatColor.WHITE + "/login" + ChatColor.RED + " first.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!needsEnforcement(event.getPlayer().getUniqueId())) {
            return;
        }

        String message = event.getMessage().toLowerCase();
        boolean allowed = ALLOWED_COMMANDS.stream().anyMatch(message::startsWith);

        if (!allowed) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Please " + ChatColor.WHITE + "/register" + ChatColor.RED + " or " + ChatColor.WHITE + "/login" + ChatColor.RED + " first.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!needsEnforcement(event.getPlayer().getUniqueId())) {
            return;
        }

        if (event.getAction() == Action.LEFT_CLICK_AIR
                || event.getAction() == Action.LEFT_CLICK_BLOCK
                || event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
        }
    }

    private boolean needsEnforcement(UUID uuid) {
        AuthManager authManager = plugin.getAuthManager();
        return !authManager.isAuthenticated(uuid);
    }

    private boolean hasMovedBlock(PlayerMoveEvent event) {
        return event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ();
    }
}
