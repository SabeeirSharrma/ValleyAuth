package com.valleyrealm.valleyauth.command;

import com.valleyrealm.valleyauth.ValleyAuth;
import com.valleyrealm.valleyauth.cert.CertificateValidator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ValleyAuthCommandExecutor implements CommandExecutor, TabCompleter {

    private final ValleyAuth plugin;

    public ValleyAuthCommandExecutor(ValleyAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("valleyauth.admin")) {
            sender.sendMessage("§c[Valley Auth] You don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "status":
                handleStatus(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "help":
                handleHelp(sender);
                break;
            default:
                sendUsage(sender);
                break;
        }

        return true;
    }

    private void handleStatus(CommandSender sender) {
        String version = plugin.getDescription().getVersion();
        String scanResult = plugin.getIdentityManager().getStorageScanResult();
        sender.sendMessage("§a[Valley Auth] Version: " + version);
        sender.sendMessage("§a[Valley Auth] " + scanResult);

        CertificateValidator validator = plugin.getCertificateValidator();
        if (validator != null) {
            var certStatus = validator.getCertificateStatus();
            String coreCert = certStatus.getOrDefault("coreCert", "N/A");
            String status = certStatus.getOrDefault("certStatus", "N/A");
            String caStatus = certStatus.getOrDefault("caStatus", "N/A");

            sender.sendMessage("§e[Valley Auth] Certificate: §f" + coreCert + " §7(" + status + ")");
            sender.sendMessage("§e[Valley Auth] CA: §f" + caStatus);
        } else {
            sender.sendMessage("§e[Valley Auth] Certificate system: §cNot initialized");
        }
    }

    private void handleReload(CommandSender sender) {
        plugin.getConfigManager().loadConfig();
        sender.sendMessage("§a[Valley Auth] Configuration reloaded.");
    }

    private void handleHelp(CommandSender sender) {
        sender.sendMessage("§e[Valley Auth] Available commands:");
        sender.sendMessage("§e  /valleyauth status - View plugin status");
        sender.sendMessage("§e  /valleyauth reload - Reload configuration");
        sender.sendMessage("§e  /valleyauth help - Show this help message");
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§e[Valley Auth] Usage: /valleyauth <status|reload|help>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("valleyauth.admin")) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            return Arrays.asList("status", "reload", "help").stream()
                    .filter(s -> s.startsWith(partial))
                    .collect(Collectors.toList());
        }

        return new ArrayList<>();
    }
}
