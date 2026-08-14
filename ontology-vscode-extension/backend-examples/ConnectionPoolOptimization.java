/**
 * Connection Pool Optimization for Concurrent Uploads
 *
 * Performance Impact: Handles 10+ concurrent uploads efficiently
 *
 * Why this is important:
 * - Multiple users uploading simultaneously
 * - Connection creation is expensive
 * - Reusing connections is much faster
 * - Prevents connection exhaustion
 *
 * Use this when:
 * - You have multiple users
 * - Concurrent uploads are common
 * - You see "too many connections" errors
 */

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.manager.RepositoryManager;
import org.eclipse.rdf4j.repository.manager.RepositoryProvider;

import javax.sql.DataSource;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionPoolOptimization {

    private final Repository repository;
    private final ExecutorService executorService;
    private final Semaphore connectionSemaphore;
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    // Configuration
    private static final int MAX_CONCURRENT_IMPORTS = 5;  // Limit concurrent imports
    private static final int THREAD_POOL_SIZE = 10;       // Thread pool size
    private static final int CONNECTION_TIMEOUT_SECONDS = 300; // 5 minutes

    public ConnectionPoolOptimization(Repository repository) {
        this.repository = repository;
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        this.connectionSemaphore = new Semaphore(MAX_CONCURRENT_IMPORTS);
    }

    /**
     * Submit an import task with automatic connection management
     */
    public CompletableFuture<ImportResult> submitImport(
            String projectId,
            Callable<Void> importTask) {

        return CompletableFuture.supplyAsync(() -> {
            boolean acquired = false;
            RepositoryConnection conn = null;

            try {
                // Acquire semaphore (blocks if too many concurrent imports)
                System.out.println("[" + projectId + "] Waiting for available slot...");
                acquired = connectionSemaphore.tryAcquire(
                    CONNECTION_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                );

                if (!acquired) {
                    throw new TimeoutException(
                        "Timeout waiting for import slot. Too many concurrent imports."
                    );
                }

                System.out.println("[" + projectId + "] Acquired import slot " +
                                 "(active: " + activeConnections.incrementAndGet() + ")");

                // Get connection from pool
                conn = repository.getConnection();
                long startTime = System.currentTimeMillis();

                // Execute import
                importTask.call();

                long duration = System.currentTimeMillis() - startTime;

                return new ImportResult(
                    projectId,
                    true,
                    duration,
                    null
                );

            } catch (Exception e) {
                System.err.println("[" + projectId + "] Import failed: " + e.getMessage());
                return new ImportResult(
                    projectId,
                    false,
                    0,
                    e.getMessage()
                );

            } finally {
                // Clean up
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (Exception e) {
                        System.err.println("Failed to close connection: " + e.getMessage());
                    }
                }

                if (acquired) {
                    connectionSemaphore.release();
                    int active = activeConnections.decrementAndGet();
                    System.out.println("[" + projectId + "] Released import slot " +
                                     "(active: " + active + ")");
                }
            }
        }, executorService);
    }

    /**
     * Submit multiple imports and track their progress
     */
    public CompletableFuture<List<ImportResult>> submitBatchImports(
            List<ImportTask> tasks) {

        List<CompletableFuture<ImportResult>> futures = new ArrayList<>();

        for (ImportTask task : tasks) {
            CompletableFuture<ImportResult> future = submitImport(
                task.projectId,
                task.callable
            );
            futures.add(future);
        }

        // Wait for all to complete
        return CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        ).thenApply(v ->
            futures.stream()
                   .map(CompletableFuture::join)
                   .collect(java.util.stream.Collectors.toList())
        );
    }

    /**
     * Get pool statistics
     */
    public PoolStats getStats() {
        return new PoolStats(
            activeConnections.get(),
            MAX_CONCURRENT_IMPORTS - connectionSemaphore.availablePermits(),
            connectionSemaphore.availablePermits(),
            executorService instanceof ThreadPoolExecutor ?
                ((ThreadPoolExecutor) executorService).getActiveCount() : 0
        );
    }

    /**
     * Shutdown the pool gracefully
     */
    public void shutdown() {
        System.out.println("Shutting down connection pool...");

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }

        System.out.println("Connection pool shut down ✓");
    }

    // ==================== Supporting Classes ====================

    public static class ImportTask {
        public final String projectId;
        public final Callable<Void> callable;

        public ImportTask(String projectId, Callable<Void> callable) {
            this.projectId = projectId;
            this.callable = callable;
        }
    }

    public static class ImportResult {
        public final String projectId;
        public final boolean success;
        public final long durationMs;
        public final String error;

        public ImportResult(String projectId, boolean success, long durationMs, String error) {
            this.projectId = projectId;
            this.success = success;
            this.durationMs = durationMs;
            this.error = error;
        }

        @Override
        public String toString() {
            return String.format(
                "ImportResult{project=%s, success=%s, duration=%.2fs%s}",
                projectId,
                success,
                durationMs / 1000.0,
                error != null ? ", error=" + error : ""
            );
        }
    }

    public static class PoolStats {
        public final int activeConnections;
        public final int usedSlots;
        public final int availableSlots;
        public final int activeThreads;

        public PoolStats(int activeConnections, int usedSlots,
                        int availableSlots, int activeThreads) {
            this.activeConnections = activeConnections;
            this.usedSlots = usedSlots;
            this.availableSlots = availableSlots;
            this.activeThreads = activeThreads;
        }

        @Override
        public String toString() {
            return String.format(
                "Pool Stats: %d active connections, %d/%d slots used, %d threads",
                activeConnections,
                usedSlots,
                usedSlots + availableSlots,
                activeThreads
            );
        }
    }

    // ==================== Usage Example ====================

    public static void main(String[] args) throws Exception {
        // Initialize repository (example)
        RepositoryManager repoManager = RepositoryProvider.getRepositoryManager(
            "http://localhost:7200"
        );
        Repository repo = repoManager.getRepository("ontology-repo");

        // Create pool
        ConnectionPoolOptimization pool = new ConnectionPoolOptimization(repo);

        // Submit multiple imports
        List<ImportTask> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final int taskId = i;
            tasks.add(new ImportTask(
                "project-" + i,
                () -> {
                    System.out.println("Processing import " + taskId);
                    Thread.sleep(5000); // Simulate import
                    return null;
                }
            ));
        }

        // Submit and wait
        System.out.println("Submitting 10 imports (max 5 concurrent)...");
        CompletableFuture<List<ImportResult>> results = pool.submitBatchImports(tasks);

        // Monitor progress
        while (!results.isDone()) {
            System.out.println(pool.getStats());
            Thread.sleep(1000);
        }

        // Print results
        System.out.println("\n=== Results ===");
        for (ImportResult result : results.get()) {
            System.out.println(result);
        }

        // Shutdown
        pool.shutdown();
    }
}
