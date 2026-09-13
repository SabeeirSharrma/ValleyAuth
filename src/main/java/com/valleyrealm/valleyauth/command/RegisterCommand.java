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
 * /register command for Offline Java identities.
 * Section 9 of the spec.
 *
 * Usage: /register <password> <confirmPassword>
 *
 * The password belongs to the Offline identity.
 * Premium Java and Bedrock identities do not have Valley Auth passwords.
 */
public class RegisterCommand implements CommandExecutor {

    private final ValleyAuthPlugin plugin;

    public RegisterCommand(ValleyAuthPlugin plugin) {
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

        // Must be an Offline identity to register
        AuthState state = authManager.getAuthState(player.getUniqueId());
        if (state != AuthState.OFFLINE) {
            player.sendMessage("Only Offline accounts can register a password.");
            return true;
        }

        // Get the bare username (without '-' prefix)
        String canonicalName = player.getName();
        String bareName = plugin.getIdentityManager().getBareName(canonicalName);

        if (passwordStore.isRegistered(bareName)) {
            player.sendMessage("This account is already registered. Use /login instead.");
            return true;
        }

        if (args.length != 2) {
            player.sendMessage("Usage: /register <password> <confirmPassword>");
            return true;
        }

        String password = args[0];
        String confirmPassword = args[1];

        if (!password.equals(confirmPassword)) {
            player.sendMessage("Passwords do not match.");
            return true;
        }

        if (password.length() < 6) {
            player.sendMessage("Password must be at least 6 characters.");
            return true;
        }

        if (passwordStore.register(bareName, password)) {
            player.sendMessage("Registration successful! You can now use /login.");
            authManager.setAuthenticated(player.getUniqueId(), AuthState.AUTHENTICATED);
        } else {
            player.sendMessage("Registration failed. Please try again.");
        }

        return true;
    }
}
