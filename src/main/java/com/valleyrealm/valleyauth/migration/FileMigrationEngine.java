package com.valleyrealm.valleyauth.migration;

import com.valleyrealm.valleyauth.ValleyAuth;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Engine for migrating player data files by replacing UUIDs.
 *
 * Two-phase design (per spec):
 * - STARTUP: MigrationIndex caches candidate paths + format only (no file I/O).
 * - MIGRATION TIME: This engine opens only the indexed files, searches for the
 *   exact old UUID, replaces with new UUID, and closes files.
 *
 * Security: Only replaces the exact source UUID associated with the migration.
 * Never globally replaces arbitrary UUID-looking sequences.
 * No file handles are held between startup and actual migration.
 */
public class FileMigrationEngine {

    private final ValleyAuth plugin;

    // Supported file extensions
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "json", "yml", "yaml", "properties", "txt", "dat", "conf", "cfg"
    );

    // Database extensions to exclude
    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
        "db", "sqlite", "sqlite3", "mdb"
    );

    public FileMigrationEngine(ValleyAuth plugin) {
        this.plugin = plugin;
    }

    /**
     * MIGRATION TIME: Migrate files using a pre-built index.
     * Opens only the indexed supported files, searches for exact old UUID,
     * replaces with new UUID, closes files.
     *
     * @param index The cached migration index from startup
     * @param oldUuid The exact source UUID to replace
     * @param newUuid The destination UUID
     * @param job The migration job to update
     * @return Migration result
     */
    public MigrationResult migrateFromIndex(MigrationIndex index, String oldUuid, String newUuid, MigrationJob job) {
        List<MigrationIndex.IndexedFile> supportedFiles = index.getSupportedFiles();
        int processedFiles = 0;
        int failedFiles = 0;
        List<String> warnings = new ArrayList<>();

        for (MigrationIndex.IndexedFile entry : supportedFiles) {
            Path path = entry.getPath();
            try {
                if (!Files.exists(path)) {
                    job.addSkippedFile(path.toString());
                    continue;
                }
                boolean modified = migrateFile(path, oldUuid, newUuid);
                if (modified) {
                    processedFiles++;
                    job.addProcessedFile(path.toString());
                } else {
                    job.addSkippedFile(path.toString());
                }
            } catch (Exception e) {
                failedFiles++;
                job.addFailedFile(path.toString());
                warnings.add("Failed to process " + path + ": " + e.getMessage());
            }
        }

        if (failedFiles > 0) {
            return MigrationResult.partial(processedFiles, warnings);
        }
        return MigrationResult.success(processedFiles, warnings);
    }

    /**
     * Migrate a single file by replacing UUID.
     * Opens, reads, modifies, and closes the file — no handles held after return.
     */
    public boolean migrateFile(Path filePath, String oldUuid, String newUuid) throws IOException {
        String fileName = filePath.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".json")) {
            return migrateJsonFile(filePath, oldUuid, newUuid);
        } else if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
            return migrateYamlFile(filePath, oldUuid, newUuid);
        } else if (fileName.endsWith(".properties")) {
            return migratePropertiesFile(filePath, oldUuid, newUuid);
        } else {
            return migratePlainTextFile(filePath, oldUuid, newUuid);
        }
    }

    private boolean migrateJsonFile(Path filePath, String oldUuid, String newUuid) throws IOException {
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        
        if (!content.contains(oldUuid)) {
            return false;
        }

        String newContent = content.replace(oldUuid, newUuid);
        Files.writeString(filePath, newContent, StandardCharsets.UTF_8);
        
        plugin.getLogger().info("[Migration] Updated JSON: " + filePath);
        return true;
    }

    private boolean migrateYamlFile(Path filePath, String oldUuid, String newUuid) throws IOException {
        return migratePlainTextFile(filePath, oldUuid, newUuid);
    }

    private boolean migratePropertiesFile(Path filePath, String oldUuid, String newUuid) throws IOException {
        return migratePlainTextFile(filePath, oldUuid, newUuid);
    }

    private boolean migratePlainTextFile(Path filePath, String oldUuid, String newUuid) throws IOException {
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        
        if (!content.contains(oldUuid)) {
            return false;
        }

        String newContent = content.replace(oldUuid, newUuid);
        Files.writeString(filePath, newContent, StandardCharsets.UTF_8);
        
        plugin.getLogger().info("[Migration] Updated text file: " + filePath);
        return true;
    }

    /**
     * Check if a file should be excluded (database files).
     */
    private boolean isExcludedFile(String fileName) {
        return EXCLUDED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    /**
     * Check if a file format is supported for migration.
     */
    private boolean isSupportedFile(String fileName) {
        return SUPPORTED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    // Result class
    public static class StorageScanResult {
        private final int total;
        private final int supported;
        private final int excluded;

        public StorageScanResult(int total, int supported, int excluded) {
            this.total = total;
            this.supported = supported;
            this.excluded = excluded;
        }

        public int getTotal() { return total; }
        public int getSupported() { return supported; }
        public int getExcluded() { return excluded; }
    }
}
