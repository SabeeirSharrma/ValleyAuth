package com.valleyrealm.valleyauth.command;

import com.valleyrealm.valleyauth.ValleyAuthPlugin;
import com.valleyrealm.valleyauth.auth.AuthManager;
import com.valleyrealm.valleyauth.auth.AuthState;
import com.valleyrealm.valleyauth.auth.PasswordStore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /login command for Offline Java identities.
 * Section 9 of the spec.
 *
 * Usage: /login <password>
 *
 * The password belongs to the Offline identity.
 * Premium Java and Bedrock identities do not have Valley Auth passwords.
 */
public class LoginCommand implements CommandExecutor {

    private final ValleyAuthPlugin plugin;

    public LoginCommand(ValleyAuthPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        AuthManager authManager = plugin.getAuthManager();
        PasswordStore passwordStore = authManager.getPasswordStore();

        // Must be an Offline identity to login
        AuthState state = authManager.getAuthState(player.getUniqueId());
        if (state != AuthState.OFFLINE) {
            player.sendMessage("Only Offline accounts need to login.");
            return true;
        }

        if (authManager.isAuthenticated(player.getUniqueId())) {
            player.sendMessage("You are already authenticated.");
            return true;
        }

        // Get the bare username (without '-' prefix)
        String canonicalName = player.getName();
        String bareName = plugin.getIdentityManager().getBareName(canonicalName);

        if (!passwordStore.isRegistered(bareName)) {
            player.sendMessage("This account is not registered. Use /register first.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage("Usage: /login <password>");
            return true;
        }

        String password = args[0];

        if (passwordStore.verify(bareName, password)) {
            authManager.setAuthenticated(player.getUniqueId(), AuthState.AUTHENTICATED);
            player.sendMessage("Login successful!");
        } else {
            player.sendMessage("Incorrect password.");
        }

        return true;
    }
}
