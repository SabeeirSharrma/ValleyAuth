package com.valleyrealm.valleyauth.luckperms;

import com.valleyrealm.valleyauth.ValleyAuth;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.context.Context;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Adapter for LuckPerms permission migration.
 *
 * Copies all permissions, groups, and meta from an old UUID to a new UUID
 * during offline→premium player migration.
 *
 * LuckPerms is a soft dependency — this adapter gracefully no-ops when
 * LuckPerms is not installed on the server.
 */
public class LuckPermsAdapter {

    private final ValleyAuth plugin;
    private LuckPerms luckPermsApi;
    private boolean available = false;

    public LuckPermsAdapter(ValleyAuth plugin) {
        this.plugin = plugin;
    }

    /**
     * Detect LuckPerms and hook the API.
     * Called once during plugin startup.
     */
    public void initialize() {
        try {
            Object luckPermsPlugin = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
            if (luckPermsPlugin == null) {
                plugin.getLogger().info("[Valley Auth] LuckPerms not found — permission migration disabled.");
                return;
            }

            luckPermsApi = LuckPermsProvider.get();
            available = true;
            plugin.getLogger().info("[Valley Auth] LuckPerms detected — permission migration enabled.");

        } catch (IllegalStateException e) {
            plugin.getLogger().info("[Valley Auth] LuckPerms API not available — permission migration disabled.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Valley Auth] Failed to initialize LuckPerms adapter", e);
        }
    }

    /**
     * Check if LuckPerms is available and ready for migration.
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Migrate all LuckPerms permissions, groups, and meta from old UUID to new UUID.
     *
     * @param oldUuid the source (offline) player UUID
     * @param newUuid the destination (premium) player UUID
     */
    public void migratePermissions(UUID oldUuid, UUID newUuid) {
        if (!available) {
            return;
        }

        try {
            plugin.getLogger().info("[Valley Auth] Migrating LuckPerms permissions from " +
                oldUuid + " to " + newUuid);

            User oldUser = luckPermsApi.getUserManager().loadUser(oldUuid).join();
            if (oldUser == null) {
                plugin.getLogger().warning("[Valley Auth] LuckPerms: No data found for old UUID " +
                    oldUuid + " — skipping permission migration.");
                return;
            }

            User newUser = luckPermsApi.getUserManager().loadUser(newUuid).join();
            if (newUser == null) {
                plugin.getLogger().severe("[Valley Auth] LuckPerms: Could not load user for new UUID " +
                    newUuid + " — skipping permission migration.");
                return;
            }

            int migratedCount = 0;

            for (Node node : oldUser.getNodes()) {
                var nodeBuilder = Node.builder(node.getKey())
                    .value(node.getValue());

                if (node.hasExpiry()) {
                    nodeBuilder.expiry(node.getExpiry());
                }

                if (!node.getContexts().isEmpty()) {
                    for (Context ctx : node.getContexts()) {
                        nodeBuilder.withContext(ctx.getKey(), ctx.getValue());
                    }
                }

                newUser.data().add(nodeBuilder.build());
                migratedCount++;
            }

            luckPermsApi.getUserManager().saveUser(newUser).join();

            plugin.getLogger().info("[Valley Auth] LuckPerms: Successfully migrated " +
                migratedCount + " permission nodes from " + oldUuid + " to " + newUuid);

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                "[Valley Auth] LuckPerms permission migration failed for " +
                oldUuid + " → " + newUuid, e);
        }
    }

    /**
     * Shutdown hook — LuckPerms manages its own lifecycle.
     */
    public void shutdown() {
        // No-op: LuckPerms handles its own cleanup
    }
}
