

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.manager.RepositoryManager;
import org.eclipse.rdf4j.repository.manager.RepositoryProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/ontology")
public class CompleteOptimizedController {

    @Autowired
    private Repository graphDbRepository;

    private final ConnectionPoolOptimization connectionPool;
    private final PerformanceMonitoring monitor;
    private final BatchInsertOptimization batchImporter;

    public CompleteOptimizedController(Repository repository) {
        this.graphDbRepository = repository;
        this.connectionPool = new ConnectionPoolOptimization(repository);
        this.monitor = PerformanceMonitoring.getInstance();
        this.batchImporter = new BatchInsertOptimization();
    }

    @PostMapping("/upload/{projectId}")
    public ResponseEntity<?> uploadOntology(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "compressed", defaultValue = "false") boolean compressed,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "ownerEmail", required = false) String ownerEmail
    ) {
        try {
            long startTime = System.currentTimeMillis();
            String fileName = file.getOriginalFilename();
            long fileSize = file.getSize();
            double fileSizeMB = fileSize / (1024.0 * 1024.0);

            System.out.println("=== OPTIMIZED IMPORT START ===");
            System.out.println("Project: " + projectId);
            System.out.println("File: " + fileName + " (" + String.format("%.1f", fileSizeMB) + " MB)");
            System.out.println("Compressed: " + compressed);
            System.out.println("Owner: " + ownerEmail);

            monitor.startImport(projectId, fileSize, fileName);

            boolean isLargeFile = fileSize > 50 * 1024 * 1024;

            if (isLargeFile) {

                System.out.println("Large file detected - processing asynchronously");

                connectionPool.submitImport(projectId, () -> {
                    importWithAllOptimizations(projectId, file, compressed);
                    return null;
                });

                return ResponseEntity.accepted().body(Map.of(
                    "message", "Large file accepted. Processing in background...",
                    "projectId", projectId,
                    "fileSizeMB", fileSizeMB,
                    "estimatedTimeMinutes", Math.ceil(fileSizeMB / 10),
                    "status", "processing"
                ));

            } else {

                BatchInsertOptimization.ImportStats stats =
                    importWithAllOptimizations(projectId, file, compressed);

                long totalTime = System.currentTimeMillis() - startTime;

                return ResponseEntity.ok(Map.of(
                    "message", "Import completed successfully",
                    "projectId", projectId,
                    "stats", Map.of(
                        "totalTriples", stats.totalTriples,
                        "durationSeconds", stats.durationMs / 1000.0,
                        "triplesPerSecond", stats.triplesPerSecond,
                        "fileSizeMB", fileSizeMB
                    )
                ));
            }

        } catch (Exception e) {
            System.err.println("Import failed: " + e.getMessage());
            e.printStackTrace();

            monitor.completeImport(projectId, false, 0, e.getMessage());

            return ResponseEntity.status(500).body(Map.of(
                "error", "Import failed: " + e.getMessage(),
                "projectId", projectId
            ));
        }
    }

    private BatchInsertOptimization.ImportStats importWithAllOptimizations(
            String projectId,
            MultipartFile file,
            boolean compressed) throws Exception {

        File tempFile = File.createTempFile("ontology-", ".owl");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(file.getBytes());
        }

        try {

            BatchInsertOptimization.ProgressCallback progressCallback = (processed, speed, percent) -> {
                monitor.updateProgress(projectId, processed, "importing");
                System.out.println(String.format(
                    "[%s] Progress: %d%% | %,d triples | %.0f t/s",
                    projectId, percent, processed, speed
                ));
            };

            BatchInsertOptimization.ImportStats stats =
                batchImporter.importAutoOptimized(
                    graphDbRepository,
                    tempFile,
                    compressed,
                    progressCallback
                );

            monitor.completeImport(projectId, true, stats.totalTriples, null);

            return stats;

        } finally {

            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    @GetMapping("/import-status/{projectId}")
    public ResponseEntity<?> getImportStatus(@PathVariable String projectId) {

        PerformanceMonitoring.ImportMetrics metrics = monitor.getActiveImports()
            .stream()
            .filter(m -> m.projectId.equals(projectId))
            .findFirst()
            .orElse(null);

        if (metrics != null) {
            return ResponseEntity.ok(Map.of(
                "projectId", projectId,
                "status", "processing",
                "phase", metrics.currentPhase,
                "durationSeconds", metrics.getDurationSeconds(),
                "processedTriples", metrics.processedTriples,
                "triplesPerSecond", metrics.getTriplesPerSecond(),
                "fileSizeMB", metrics.getFileSizeMB()
            ));
        }

        PerformanceMonitoring.ImportMetrics completed = monitor.getRecentImports(50)
            .stream()
            .filter(m -> m.projectId.equals(projectId))
            .findFirst()
            .orElse(null);

        if (completed != null) {
            return ResponseEntity.ok(Map.of(
                "projectId", projectId,
                "status", completed.success ? "completed" : "failed",
                "durationSeconds", completed.getDurationSeconds(),
                "totalTriples", completed.totalTriples,
                "triplesPerSecond", completed.getTriplesPerSecond(),
                "error", completed.error != null ? completed.error : ""
            ));
        }

        return ResponseEntity.status(404).body(Map.of(
            "error", "Import not found",
            "projectId", projectId
        ));
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        PerformanceMonitoring.OverallStats stats = monitor.getOverallStats();
        ConnectionPoolOptimization.PoolStats poolStats = connectionPool.getStats();

        return ResponseEntity.ok(Map.of(
            "imports", Map.of(
                "total", stats.totalImports,
                "successful", stats.successfulImports,
                "failed", stats.failedImports,
                "active", stats.activeImports,
                "successRate", stats.getSuccessRate()
            ),
            "performance", Map.of(
                "avgDurationSeconds", stats.avgDurationSeconds,
                "avgTriplesPerSecond", stats.avgTriplesPerSecond,
                "avgFileSizeMB", stats.avgFileSizeMB
            ),
            "pool", Map.of(
                "activeConnections", poolStats.activeConnections,
                "usedSlots", poolStats.usedSlots,
                "availableSlots", poolStats.availableSlots,
                "activeThreads", poolStats.activeThreads
            )
        ));
    }

    @GetMapping("/active-imports")
    public ResponseEntity<?> getActiveImports() {
        return ResponseEntity.ok(
            monitor.getActiveImports()
                .stream()
                .map(m -> Map.of(
                    "projectId", m.projectId,
                    "fileName", m.fileName,
                    "fileSizeMB", m.getFileSizeMB(),
                    "durationSeconds", m.getDurationSeconds(),
                    "phase", m.currentPhase,
                    "processedTriples", m.processedTriples,
                    "triplesPerSecond", m.getTriplesPerSecond()
                ))
                .toArray()
        );
    }

    @GetMapping("/metrics")
    public ResponseEntity<String> getPrometheusMetrics() {
        return ResponseEntity.ok()
            .header("Content-Type", "text/plain; version=0.0.4")
            .body(monitor.exportPrometheusMetrics());
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        try {

            try (RepositoryConnection conn = graphDbRepository.getConnection()) {
                long size = conn.size();
                return ResponseEntity.ok(Map.of(
                    "status", "healthy",
                    "graphdb", "connected",
                    "tripleCount", size,
                    "poolStats", connectionPool.getStats()
                ));
            }
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                "status", "unhealthy",
                "error", e.getMessage()
            ));
        }
    }

    @PreDestroy
    public void shutdown() {
        System.out.println("Shutting down optimized controller...");
        connectionPool.shutdown();
    }
}
