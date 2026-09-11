package com.valleyrealm.valleyauth.migration;

/**
 * States a migration job can be in.
 * 
 * Lifecycle:
 * PENDING -> QUEUED -> RUNNING -> COMPLETED
 *                           |
 *                           +-> FAILED
 * 
 * A completed migration cannot be restarted.
 */
public enum MigrationState {
    /**
     * Migration created but not yet queued.
     */
    PENDING,
    
    /**
     * Migration is waiting for an available slot.
     */
    QUEUED,
    
    /**
     * Migration is actively executing.
     */
    RUNNING,
    
    /**
     * Migration completed successfully.
     */
    COMPLETED,
    
    /**
     * Migration failed. Reason is recorded.
     */
    FAILED;

    /**
     * Check if this state can transition to another state.
     */
    public boolean canTransitionTo(MigrationState target) {
        switch (this) {
            case PENDING:
                return target == QUEUED || target == FAILED;
            case QUEUED:
                return target == RUNNING || target == FAILED;
            case RUNNING:
                return target == COMPLETED || target == FAILED;
            case COMPLETED:
                return false; // Terminal state
            case FAILED:
                return false; // Terminal state
            default:
                return false;
        }
    }

    /**
     * Check if this is a terminal state.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
