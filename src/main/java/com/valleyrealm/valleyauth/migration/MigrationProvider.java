package com.valleyrealm.valleyauth.migration;

import java.util.UUID;

/**
 * Interface for plugins that want to participate in Valley Auth migrations.
 * 
 * Plugins with specialized storage (databases, custom formats) can register
 * a migration provider to handle their specific data.
 * 
 * Valley Auth provides:
 * - The authoritative UUID mapping (oldUuid -> newUuid)
 * - Migration context (source/destination identities, state)
 * 
 * The provider is responsible for:
 * - Understanding its own storage
 * - Database access and connection
 * - Schema and table knowledge
 * - UUID column identification
 * - Transaction handling
 * - Data transformation
 * - Cleanup
 * 
 * Registration requires MIGRATION_PROVIDER certificate capability.
 */
public interface MigrationProvider {

    /**
     * Get the unique identifier for this provider.
     */
    String getProviderId();

    /**
     * Get a human-readable name for this provider.
     */
    String getProviderName();

    /**
     * Check if this provider can handle the given migration.
     * 
     * @param type The migration type
     * @return true if this provider can participate
     */
    boolean canHandle(MigrationType type);

    /**
     * Execute the migration for this provider's storage.
     * 
     * @param oldUuid The source UUID
     * @param newUuid The destination UUID
     * @param context Additional migration context
     * @return Migration result
     */
    MigrationResult migrate(UUID oldUuid, UUID newUuid, MigrationContext context);

    /**
     * Check if this provider supports rollback.
     */
    boolean supportsRollback();

    /**
     * Attempt to rollback a failed migration.
     * 
     * @param context The migration context with rollback information
     * @return true if rollback succeeded
     */
    boolean rollback(MigrationContext context);

    /**
     * Get estimated duration for this migration in milliseconds.
     */
    long getEstimatedDuration(UUID oldUuid, UUID newUuid);
}
