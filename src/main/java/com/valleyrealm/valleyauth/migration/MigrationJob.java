package com.valleyrealm.valleyauth.migration;

import com.valleyrealm.valleyauth.identity.Identity;
import com.valleyrealm.valleyauth.vlink.VLinkManager.VLinkSession;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a single autonomous migration job.
 * 
 * Each migration contains its own complete state:
 * - Source and destination identities
 * - UUID mapping
 * - VLink/session information
 * - Current state and progress
 * - Errors and rollback information
 * - Registered migration providers
 * 
 * One migration must never depend on another migration succeeding.
 */
public class MigrationJob {

    private final String jobId;
    private final Identity sourceIdentity;
    private final Identity destinationIdentity;
    private final MigrationType migrationType;
    private final VLinkSession vlinkSession;
    private final long createdAt;
    
    private MigrationState state;
    private String statusMessage;
    private int progress; // 0-100
    private String error;
    
    // UUID mapping for this migration
    private final UUID oldUuid;
    private final UUID newUuid;
    
    // Files processed
    private final List<String> processedFiles = new ArrayList<>();
    private final List<String> skippedFiles = new ArrayList<>();
    private final List<String> failedFiles = new ArrayList<>();
    
    // Rollback information
    private boolean rollbackAvailable;
    private String rollbackPath;
    
    // Migration providers that participated
    private final List<String> participatingProviders = new ArrayList<>();

    public MigrationJob(String jobId, Identity sourceIdentity, Identity destinationIdentity,
                       MigrationType migrationType, VLinkSession vlinkSession) {
        this.jobId = jobId;
        this.sourceIdentity = sourceIdentity;
        this.destinationIdentity = destinationIdentity;
        this.migrationType = migrationType;
        this.vlinkSession = vlinkSession;
        this.createdAt = System.currentTimeMillis();
        
        this.oldUuid = sourceIdentity.getUuid();
        this.newUuid = destinationIdentity.getUuid();
        
        this.state = MigrationState.PENDING;
        this.statusMessage = "Migration created";
        this.progress = 0;
    }

    /**
     * Transition to a new state.
     */
    public boolean transitionTo(MigrationState newState) {
        if (!state.canTransitionTo(newState)) {
            return false;
        }
        this.state = newState;
        return true;
    }

    /**
     * Mark migration as queued.
     */
    public void queue() {
        transitionTo(MigrationState.QUEUED);
        this.statusMessage = "Migration queued";
    }

    /**
     * Mark migration as running.
     */
    public void start() {
        transitionTo(MigrationState.RUNNING);
        this.statusMessage = "Migration running";
    }

    /**
     * Mark migration as completed.
     */
    public void complete() {
        transitionTo(MigrationState.COMPLETED);
        this.statusMessage = "Migration completed successfully";
        this.progress = 100;
    }

    /**
     * Mark migration as failed with reason.
     */
    public void fail(String reason) {
        transitionTo(MigrationState.FAILED);
        this.statusMessage = "Migration failed: " + reason;
        this.error = reason;
    }

    /**
     * Update progress.
     */
    public void setProgress(int progress) {
        this.progress = Math.max(0, Math.min(100, progress));
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    /**
     * Add a processed file.
     */
    public void addProcessedFile(String filePath) {
        processedFiles.add(filePath);
    }

    /**
     * Add a skipped file.
     */
    public void addSkippedFile(String filePath) {
        skippedFiles.add(filePath);
    }

    /**
     * Add a failed file.
     */
    public void addFailedFile(String filePath) {
        failedFiles.add(filePath);
    }

    /**
     * Record a participating migration provider.
     */
    public void addParticipatingProvider(String providerId) {
        participatingProviders.add(providerId);
    }

    // Getters
    public String getJobId() { return jobId; }
    public Identity getSourceIdentity() { return sourceIdentity; }
    public Identity getDestinationIdentity() { return destinationIdentity; }
    public MigrationType getMigrationType() { return migrationType; }
    public VLinkSession getVlinkSession() { return vlinkSession; }
    public long getCreatedAt() { return createdAt; }
    
    public MigrationState getState() { return state; }
    public String getStatusMessage() { return statusMessage; }
    public int getProgress() { return progress; }
    public String getError() { return error; }
    
    public UUID getOldUuid() { return oldUuid; }
    public UUID getNewUuid() { return newUuid; }
    
    public List<String> getProcessedFiles() { return processedFiles; }
    public List<String> getSkippedFiles() { return skippedFiles; }
    public List<String> getFailedFiles() { return failedFiles; }
    
    public boolean isRollbackAvailable() { return rollbackAvailable; }
    public String getRollbackPath() { return rollbackPath; }
    
    public List<String> getParticipatingProviders() { return participatingProviders; }
}
