package com.valleyrealm.valleyauth.migration;

import com.valleyrealm.valleyauth.identity.Identity;

import java.util.Map;
import java.util.UUID;

/**
 * Context passed to migration providers during execution.
 * 
 * Contains all information needed for a provider to perform its migration.
 */
public class MigrationContext {

    private final String jobId;
    private final Identity sourceIdentity;
    private final Identity destinationIdentity;
    private final MigrationType migrationType;
    private final UUID oldUuid;
    private final UUID newUuid;
    private final Map<String, Object> metadata;

    public MigrationContext(String jobId, Identity sourceIdentity, Identity destinationIdentity,
                           MigrationType migrationType, Map<String, Object> metadata) {
        this.jobId = jobId;
        this.sourceIdentity = sourceIdentity;
        this.destinationIdentity = destinationIdentity;
        this.migrationType = migrationType;
        this.oldUuid = sourceIdentity.getUuid();
        this.newUuid = destinationIdentity.getUuid();
        this.metadata = metadata;
    }

    /**
     * Get a metadata value by key.
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key) {
        return (T) metadata.get(key);
    }

    /**
     * Get a metadata value with default.
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, T defaultValue) {
        Object value = metadata.get(key);
        return value != null ? (T) value : defaultValue;
    }

    // Getters
    public String getJobId() { return jobId; }
    public Identity getSourceIdentity() { return sourceIdentity; }
    public Identity getDestinationIdentity() { return destinationIdentity; }
    public MigrationType getMigrationType() { return migrationType; }
    public UUID getOldUuid() { return oldUuid; }
    public UUID getNewUuid() { return newUuid; }
    public Map<String, Object> getMetadata() { return metadata; }
}
