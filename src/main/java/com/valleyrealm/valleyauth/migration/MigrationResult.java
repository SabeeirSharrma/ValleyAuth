package com.valleyrealm.valleyauth.migration;

import java.util.List;

/**
 * Result of a migration provider's execution.
 */
public class MigrationResult {

    private final boolean success;
    private final String message;
    private final int recordsMigrated;
    private final List<String> warnings;
    private final String rollbackPath;

    private MigrationResult(boolean success, String message, int recordsMigrated,
                           List<String> warnings, String rollbackPath) {
        this.success = success;
        this.message = message;
        this.recordsMigrated = recordsMigrated;
        this.warnings = warnings;
        this.rollbackPath = rollbackPath;
    }

    public static MigrationResult success(int recordsMigrated, List<String> warnings) {
        return new MigrationResult(true, "Migration completed", recordsMigrated, warnings, null);
    }

    public static MigrationResult success(int recordsMigrated, String rollbackPath) {
        return new MigrationResult(true, "Migration completed", recordsMigrated, List.of(), rollbackPath);
    }

    public static MigrationResult failure(String message) {
        return new MigrationResult(false, message, 0, List.of(), null);
    }

    public static MigrationResult partial(int recordsMigrated, List<String> warnings) {
        return new MigrationResult(true, "Migration partially completed", recordsMigrated, warnings, null);
    }

    // Getters
    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public int getRecordsMigrated() { return recordsMigrated; }
    public List<String> getWarnings() { return warnings; }
    public String getRollbackPath() { return rollbackPath; }
}
