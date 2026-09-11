package com.valleyrealm.valleyauth.migration;

import com.valleyrealm.valleyauth.ValleyAuth;
import com.valleyrealm.valleyauth.config.ConfigManager;
import com.valleyrealm.valleyauth.identity.Identity;
import com.valleyrealm.valleyauth.identity.IdentityType;
import com.valleyrealm.valleyauth.vlink.VLinkManager.VLinkSession;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages migration jobs, queue, concurrency, and execution.
 * 
 * Key behaviors:
 * - Max concurrent migrations is configurable
 * - Each migration is independent (one failure doesn't affect others)
 * - Player presence not required after queueing
 * - Proper locking around identities/data
 */
public class MigrationManager {

    private final ValleyAuth plugin;
    private final ConfigManager config;

    // Job tracking
    private final Map<String, MigrationJob> allJobs = new ConcurrentHashMap<>();
    private final AtomicInteger jobCounter = new AtomicInteger(0);

    // Queue management
    private final Queue<String> pendingQueue = new ConcurrentLinkedQueue<>();
    private final Set<String> runningJobs = ConcurrentHashMap.newKeySet();

    // Locking: one migration per identity at a time
    private final Map<UUID, ReentrantLock> identityLocks = new ConcurrentHashMap<>();

    private final List<MigrationProvider> registeredProviders = new ArrayList<>();

    // Thread pool for migration execution
    private final ExecutorService migrationExecutor;

    // Shutdown flag
    private volatile boolean shutdown = false;

    public MigrationManager(ValleyAuth plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();

        // Create thread pool based on max concurrent migrations
        int maxConcurrent = config.getMaxConcurrentMigrations();
        this.migrationExecutor = Executors.newFixedThreadPool(maxConcurrent, r -> {
            Thread t = new Thread(r, "ValleyAuth-Migration-Worker");
            t.setDaemon(true);
            return t;
        });

        // Start queue processor
        startQueueProcessor();

        plugin.getLogger().info("[Valley Auth] Migration system initialized. Max concurrent: " + maxConcurrent);
    }

    /**
     * Queue a new migration job.
     * 
     * @param sourceIdentity The offline identity being migrated
     * @param destinationIdentity The verified destination identity
     * @param vlinkSession The verified VLink session
     * @return The queued migration job
     */
    public MigrationJob queueMigration(Identity sourceIdentity, Identity destinationIdentity, VLinkSession vlinkSession) {
        // Determine migration type
        MigrationType type = determineMigrationType(sourceIdentity, destinationIdentity);
        if (type == null || !type.isCoreSupported()) {
            throw new IllegalArgumentException("Unsupported migration type");
        }

        // Generate job ID
        String jobId = "migration-" + jobCounter.incrementAndGet();

        // Create job
        MigrationJob job = new MigrationJob(jobId, sourceIdentity, destinationIdentity, type, vlinkSession);

        // Store and queue
        allJobs.put(jobId, job);
        job.queue();
        pendingQueue.add(jobId);

        plugin.getLogger().info("[Migration] Job " + jobId + " queued: " +
            sourceIdentity.getCanonicalName() + " -> " + destinationIdentity.getCanonicalName());

        return job;
    }

    /**
     * Get a migration job by ID.
     */
    public MigrationJob getJob(String jobId) {
        return allJobs.get(jobId);
    }

    /**
     * Get pending migration for a source UUID.
     */
    public MigrationJob getPendingJobForSource(UUID sourceUuid) {
        return allJobs.values().stream()
            .filter(job -> job.getSourceIdentity().getUuid().equals(sourceUuid))
            .filter(job -> job.getState() == MigrationState.QUEUED || job.getState() == MigrationState.PENDING)
            .findFirst()
            .orElse(null);
    }

    /**
     * Get all jobs for a player (by UUID).
     */
    public List<MigrationJob> getJobsForPlayer(UUID playerUuid) {
        return allJobs.values().stream()
            .filter(job -> job.getSourceIdentity().getUuid().equals(playerUuid) ||
                          job.getDestinationIdentity().getUuid().equals(playerUuid))
            .sorted(Comparator.comparingLong(MigrationJob::getCreatedAt).reversed())
            .toList();
    }

    /**
     * Get queue position for a job.
     */
    public int getQueuePosition(String jobId) {
        List<String> queueList = new ArrayList<>(pendingQueue);
        return queueList.indexOf(jobId) + 1;
    }

    /**
     * Get number of currently running migrations.
     */
    public int getRunningCount() {
        return runningJobs.size();
    }

    /**
     * Get max concurrent migrations.
     */
    public int getMaxConcurrent() {
        return config.getMaxConcurrentMigrations();
    }

    /**
     * Start the queue processor.
     * Continuously checks for available slots and starts queued migrations.
     */
    private void startQueueProcessor() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (shutdown) return;

            while (!pendingQueue.isEmpty() && runningJobs.size() < config.getMaxConcurrentMigrations()) {
                String jobId = pendingQueue.poll();
                if (jobId == null) break;

                MigrationJob job = allJobs.get(jobId);
                if (job == null || job.getState() != MigrationState.QUEUED) {
                    continue;
                }

                // Check if we can acquire locks for this migration
                if (!acquireMigrationLocks(job)) {
                    // Can't acquire locks, put back in queue
                    pendingQueue.add(jobId);
                    continue;
                }

                // Start the migration
                runningJobs.add(jobId);
                job.start();
                migrationExecutor.submit(() -> executeMigration(job));
            }
        }, 20L, 20L); // Check every second
    }

    /**
     * Execute a migration job.
     */
    private void executeMigration(MigrationJob job) {
        try {
            plugin.getLogger().info("[Migration] Starting " + job.getJobId());
            job.setProgress(0);

            MigrationIndex index = plugin.getIdentityManager().getCachedMigrationIndex();
            if (index == null || index.getSupportedCount() == 0) {
                job.setStatusMessage("No supported files found. Completing...");
                job.setProgress(100);
                job.complete();
                return;
            }

            job.setStatusMessage("Migrating indexed files...");
            plugin.getLogger().info("[Migration] Using cached index: " + index.getSupportedCount() + " supported files.");

            FileMigrationEngine engine = new FileMigrationEngine(plugin);
            String oldUuid = job.getOldUuid().toString();
            String newUuid = job.getNewUuid().toString();

            MigrationResult result = engine.migrateFromIndex(index, oldUuid, newUuid, job);
            job.setProgress(80);

            job.setStatusMessage("Invoking registered migration providers...");
            job.setProgress(85);

            for (MigrationProvider provider : registeredProviders) {
                if (provider.canHandle(job.getMigrationType())) {
                    MigrationContext context = new MigrationContext(
                        job.getJobId(),
                        job.getSourceIdentity(),
                        job.getDestinationIdentity(),
                        job.getMigrationType(),
                        Map.of()
                    );
                    MigrationResult providerResult = provider.migrate(job.getOldUuid(), job.getNewUuid(), context);
                    if (!providerResult.isSuccess()) {
                        job.addFailedFile("Provider: " + provider.getProviderId());
                    } else {
                        job.addParticipatingProvider(provider.getProviderId());
                    }
                }
            }

            job.setProgress(95);

            if (result.isSuccess()) {
                job.setStatusMessage("Migration completed successfully.");
                job.complete();
                plugin.getLogger().info("[Migration] Completed " + job.getJobId() +
                    " - files migrated: " + job.getProcessedFiles().size() +
                    ", skipped: " + job.getSkippedFiles().size() +
                    ", failed: " + job.getFailedFiles().size());
            } else {
                if (!job.getFailedFiles().isEmpty()) {
                    job.complete();
                    plugin.getLogger().warning("[Migration] Completed " + job.getJobId() +
                        " with warnings - failed files: " + job.getFailedFiles().size());
                } else {
                    job.fail(result.getMessage());
                }
            }

        } catch (Exception e) {
            plugin.getLogger().severe("[Migration] Failed " + job.getJobId() + ": " + e.getMessage());
            job.fail(e.getMessage());
        } finally {
            runningJobs.remove(job.getJobId());
            releaseMigrationLocks(job);
        }
    }

    /**
     * Acquire locks for a migration job.
     * Prevents concurrent modifications to the same identity.
     */
    private boolean acquireMigrationLocks(MigrationJob job) {
        UUID sourceUuid = job.getSourceIdentity().getUuid();
        UUID destUuid = job.getDestinationIdentity().getUuid();

        // Try to acquire both locks in consistent order to prevent deadlock
        UUID firstLock = sourceUuid.compareTo(destUuid) < 0 ? sourceUuid : destUuid;
        UUID secondLock = sourceUuid.compareTo(destUuid) < 0 ? destUuid : sourceUuid;

        ReentrantLock first = identityLocks.computeIfAbsent(firstLock, k -> new ReentrantLock());
        ReentrantLock second = identityLocks.computeIfAbsent(secondLock, k -> new ReentrantLock());

        if (first.tryLock()) {
            if (second.tryLock()) {
                return true;
            }
            first.unlock();
        }

        return false;
    }

    /**
     * Release locks after migration completes.
     */
    private void releaseMigrationLocks(MigrationJob job) {
        UUID sourceUuid = job.getSourceIdentity().getUuid();
        UUID destUuid = job.getDestinationIdentity().getUuid();

        ReentrantLock first = identityLocks.get(sourceUuid);
        ReentrantLock second = identityLocks.get(destUuid);

        if (first != null && first.isHeldByCurrentThread()) first.unlock();
        if (second != null && second.isHeldByCurrentThread()) second.unlock();
    }

    /**
     * Determine migration type from source and destination.
     * Returns null if the migration is not supported or Bedrock branch is disabled.
     */
    private MigrationType determineMigrationType(Identity source, Identity destination) {
        if (source.getType() == com.valleyrealm.valleyauth.identity.IdentityType.OFFLINE) {
            if (destination.getType() == com.valleyrealm.valleyauth.identity.IdentityType.PREMIUM) {
                return MigrationType.OFFLINE_TO_PREMIUM;
            }
            if (destination.getType() == com.valleyrealm.valleyauth.identity.IdentityType.BEDROCK) {
                if (!plugin.getIdentityManager().isBedrockEnabled()) {
                    plugin.getLogger().warning("[Migration] OFFLINE→BEDROCK migration blocked: Floodgate not available.");
                    return null;
                }
                return MigrationType.OFFLINE_TO_BEDROCK;
            }
        }
        return null;
    }

    /**
     * Shutdown the migration system.
     */
    public void shutdown() {
        shutdown = true;
        migrationExecutor.shutdownNow();
        plugin.getLogger().info("[Migration] System shutdown.");
    }

    /**
     * Get summary of all jobs for status display.
     */
    public MigrationStatus getStatus() {
        long completed = allJobs.values().stream()
            .filter(j -> j.getState() == MigrationState.COMPLETED)
            .count();
        long failed = allJobs.values().stream()
            .filter(j -> j.getState() == MigrationState.FAILED)
            .count();
        long queued = pendingQueue.size();
        long running = runningJobs.size();

        return new MigrationStatus(completed, failed, queued, running);
    }

    // Status class
    public static class MigrationStatus {
        private final long completed;
        private final long failed;
        private final long queued;
        private final long running;

        public MigrationStatus(long completed, long failed, long queued, long running) {
            this.completed = completed;
            this.failed = failed;
            this.queued = queued;
            this.running = running;
        }

        public long getCompleted() { return completed; }
        public long getFailed() { return failed; }
        public long getQueued() { return queued; }
        public long getRunning() { return running; }
    }
}
