package com.valleyrealm.valleyauth.migration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cached index of candidate migration files, built at startup.
 *
 * STARTUP PHASE: scanPluginStorage() walks the plugins directory and records
 * only file paths + detected format. No file contents are opened or read.
 * This index is cached and reused at migration time.
 *
 * MIGRATION TIME: FileMigrationEngine opens only the specific indexed files,
 * searches for the exact old UUID, replaces with new UUID, and closes.
 *
 * Security: No file handles are held open between startup and migration.
 * The search only ever matches the exact source UUID (never a global replace).
 */
public class MigrationIndex {

    private final List<IndexedFile> indexedFiles;
    private final int totalCount;
    private final int supportedCount;
    private final int excludedCount;

    public MigrationIndex(List<IndexedFile> indexedFiles, int totalCount, int supportedCount, int excludedCount) {
        this.indexedFiles = Collections.unmodifiableList(new ArrayList<>(indexedFiles));
        this.totalCount = totalCount;
        this.supportedCount = supportedCount;
        this.excludedCount = excludedCount;
    }

    /**
     * Get all indexed files (both supported and excluded, for reference).
     */
    public List<IndexedFile> getIndexedFiles() {
        return indexedFiles;
    }

    /**
     * Get only the supported (migratable) files from the index.
     */
    public List<IndexedFile> getSupportedFiles() {
        return indexedFiles.stream()
            .filter(IndexedFile::isSupported)
            .toList();
    }

    public int getTotalCount() { return totalCount; }
    public int getSupportedCount() { return supportedCount; }
    public int getExcludedCount() { return excludedCount; }

    public String toSummaryString() {
        return "Found " + totalCount + " potential player-data locations. " +
               supportedCount + " supported for automatic migration. " +
               excludedCount + " database-backed locations excluded.";
    }

    /**
     * A single indexed file entry — path + detected format only, no content read.
     */
    public static class IndexedFile {
        private final Path path;
        private final String format;
        private final boolean supported;

        public IndexedFile(Path path, String format, boolean supported) {
            this.path = path;
            this.format = format;
            this.supported = supported;
        }

        public Path getPath() { return path; }
        public String getFormat() { return format; }
        public boolean isSupported() { return supported; }
    }
}
