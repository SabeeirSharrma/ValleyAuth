package com.valleyrealm.valleyauth.command;

import com.valleyrealm.valleyauth.ValleyAuth;
import com.valleyrealm.valleyauth.auth.AuthenticationManager.AuthResult;
import com.valleyrealm.valleyauth.migration.MigrationManager;
import com.valleyrealm.valleyauth.vlink.VLinkManager;
import com.valleyrealm.valleyauth.vlink.VLinkManager.VLinkResult;
import com.valleyrealm.valleyauth.vlink.VLinkManager.VLinkSession;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class AuthCommandExecutor implements CommandExecutor, TabCompleter {

    private final ValleyAuth plugin;

    public AuthCommandExecutor(ValleyAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmdName = command.getName().toLowerCase();

        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        switch (cmdName) {
            case "register":
                return handleRegister(player, args);
            case "login":
                return handleLogin(player, args);
            case "v":
                return handleVLink(player, args);
            default:
                sendUsage(player);
                return true;
        }
    }

    private boolean handleRegister(Player player, String[] args) {
        if (!player.hasPermission("valleyauth.register")) {
            player.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage("§eUsage: /register <password>");
            return true;
        }

        AuthResult result = plugin.getAuthenticationManager().handleRegister(player.getUniqueId(), args[0]);
        sendAuthResult(player, result);
        return true;
    }

    private boolean handleLogin(Player player, String[] args) {
        if (!player.hasPermission("valleyauth.login")) {
            player.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage("§eUsage: /login <password>");
            return true;
        }

        AuthResult result = plugin.getAuthenticationManager().handleLogin(player.getUniqueId(), args[0]);
        sendAuthResult(player, result);
        return true;
    }

    private boolean handleVLink(Player player, String[] args) {
        // /v requires authentication
        if (!plugin.getAuthenticationManager().isAuthenticated(player.getUniqueId())) {
            player.sendMessage("§cYou must be logged in to use this command.");
            return true;
        }

        if (args.length < 2) {
            player.sendMessage("§cUsage: /v link <code>");
            return true;
        }

        String sub = args[0].toLowerCase();
        if (!"link".equals(sub)) {
            player.sendMessage("§cUsage: /v link <code>");
            return true;
        }

        String code = args[1];

        VLinkManager vlinkManager = plugin.getVlinkManager();
        VLinkResult result = vlinkManager.verifyCode(code, player.getUniqueId(), player.getName());

        if (!result.isSuccess()) {
            player.sendMessage("§c" + result.getMessage());
            return true;
        }

        if (!result.isVerified()) {
            player.sendMessage("§cCode verification failed.");
            return true;
        }

        VLinkSession session = result.getSession();
        MigrationManager migrationManager = plugin.getMigrationManager();
        migrationManager.queueMigration(
            session.getSourceIdentity(),
            session.getDestinationIdentity(),
            session
        );

        player.sendMessage("§aMigration queued! Check /migrate status in game.");
        return true;
    }

    private void sendAuthResult(Player player, AuthResult result) {
        if (result.isSuccess()) {
            player.sendMessage("§a" + result.getMessage());
        } else {
            player.sendMessage("§c" + result.getMessage());
        }
    }

    private void sendUsage(Player player) {
        player.sendMessage("§e[Valley Auth] Commands:");
        player.sendMessage("§e/register <password> §7- Register a new account");
        player.sendMessage("§e/login <password> §7- Login to your account");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return new ArrayList<>();
    }
}
