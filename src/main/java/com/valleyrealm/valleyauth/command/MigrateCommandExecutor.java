package com.valleyrealm.valleyauth.command;

import com.valleyrealm.valleyauth.ValleyAuth;
import com.valleyrealm.valleyauth.identity.Identity;
import com.valleyrealm.valleyauth.identity.IdentityType;
import com.valleyrealm.valleyauth.migration.MigrationJob;
import com.valleyrealm.valleyauth.migration.MigrationManager;
import com.valleyrealm.valleyauth.vlink.VLinkManager;
import com.valleyrealm.valleyauth.vlink.VLinkManager.VLinkResult;
import com.valleyrealm.valleyauth.vlink.VLinkManager.VLinkSession;
import com.valleyrealm.valleyauth.vlink.VLinkWebServer;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class MigrateCommandExecutor implements CommandExecutor, TabCompleter {

    private final ValleyAuth plugin;

    public MigrateCommandExecutor(ValleyAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        if (!sender.hasPermission("valleyauth.migrate")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            handleMigrate(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "status":
                handleStatus(player);
                break;
            case "help":
                sendUsage(player);
                break;
            default:
                sendUsage(player);
                break;
        }

        return true;
    }

    private void handleMigrate(Player player) {
        VLinkManager vlinkManager = plugin.getVlinkManager();

        // Check for existing pending session
        VLinkSession pending = vlinkManager.getPendingSessionForSource(player.getUniqueId());
        if (pending != null) {
            player.sendMessage("§cYou already have a pending migration session. Use /v link " + pending.getCode() + " to verify.");
            return;
        }

        // Get offline identity for this player
        Identity sourceIdentity = plugin.getIdentityManager().getOrCreateOffline(player.getName());

        // Create VLink session targeting Premium destination
        VLinkResult result = vlinkManager.createSession(sourceIdentity, IdentityType.PREMIUM, null);
        if (!result.isSuccess()) {
            player.sendMessage("§c" + result.getMessage());
            return;
        }

        VLinkSession session = result.getSession();
        VLinkWebServer webServer = plugin.getWebServer();
        if (webServer == null) {
            player.sendMessage("§cWeb server is not running. Contact an administrator.");
            return;
        }
        String baseUrl = webServer.getBaseUrl();

        player.sendMessage("§aOpen §f" + baseUrl + "/migrate/" + session.getCode() + " §a to configure migration");

        if (!webServer.isUsingSsl() && !plugin.getConfigManager().getVlinkDomain().isEmpty()) {
            String docsUrl = plugin.getConfigManager().getCertificateDocsUrl();
            player.sendMessage("§e⚠ §7SSL is not configured. Passwords may be transmitted insecurely. See §f" + docsUrl + "/ssl §7for setup.");
        }
    }

    private void handleStatus(Player player) {
        MigrationManager migrationManager = plugin.getMigrationManager();
        MigrationJob job = migrationManager.getPendingJobForSource(player.getUniqueId());

        if (job == null) {
            player.sendMessage("§eNo pending migration found. Use /migrate to start one.");
            return;
        }

        player.sendMessage("§e[Migration] Job: " + job.getJobId());
        player.sendMessage("§e  State: §f" + job.getState());
        player.sendMessage("§e  Status: §f" + job.getStatusMessage());
        player.sendMessage("§e  Progress: §f" + job.getProgress() + "%");

        if (job.getState() == com.valleyrealm.valleyauth.migration.MigrationState.QUEUED) {
            int position = migrationManager.getQueuePosition(job.getJobId());
            player.sendMessage("§e  Queue position: §f" + position);
        }
    }

    private void sendUsage(Player player) {
        player.sendMessage("§e[Valley Auth] Migration commands:");
        player.sendMessage("§e/migrate §7- Start a migration session");
        player.sendMessage("§e/migrate status §7- Check migration status");
        player.sendMessage("§e/migrate help §7- Show this help message");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("valleyauth.migrate")) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return Arrays.asList("status", "help").stream()
                    .filter(s -> s.startsWith(partial))
                    .collect(Collectors.toList());
        }

        return new ArrayList<>();
    }
}
