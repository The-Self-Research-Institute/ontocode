

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class PerformanceMonitoring {

    private static final PerformanceMonitoring INSTANCE = new PerformanceMonitoring();

    private final Map<String, ImportMetrics> activeImports = new ConcurrentHashMap<>();
    private final List<ImportMetrics> completedImports = new ArrayList<>();
    private final AtomicLong totalImports = new AtomicLong(0);
    private final AtomicLong successfulImports = new AtomicLong(0);
    private final AtomicLong failedImports = new AtomicLong(0);

    private static final double SLOW_IMPORT_THRESHOLD = 600.0;
    private static final double VERY_SLOW_IMPORT_THRESHOLD = 1200.0;
    private static final double POOR_PERFORMANCE_THRESHOLD = 5000.0;

    public static PerformanceMonitoring getInstance() {
        return INSTANCE;
    }

    public void startImport(String projectId, long fileSizeBytes, String fileName) {
        ImportMetrics metrics = new ImportMetrics(projectId, fileSizeBytes, fileName);
        activeImports.put(projectId, metrics);
        totalImports.incrementAndGet();

        System.out.println("[Monitor] Started tracking: " + projectId);
    }

    public void updateProgress(String projectId, long processedTriples, String phase) {
        ImportMetrics metrics = activeImports.get(projectId);
        if (metrics != null) {
            metrics.updateProgress(processedTriples, phase);

            checkPerformanceAlerts(metrics);
        }
    }

    public void completeImport(String projectId, boolean success,
                               long totalTriples, String error) {
        ImportMetrics metrics = activeImports.remove(projectId);
        if (metrics != null) {
            metrics.complete(success, totalTriples, error);

            synchronized (completedImports) {
                completedImports.add(metrics);

                if (completedImports.size() > 100) {
                    completedImports.remove(0);
                }
            }

            if (success) {
                successfulImports.incrementAndGet();
            } else {
                failedImports.incrementAndGet();
            }

            System.out.println("[Monitor] Completed: " + projectId +
                             " | " + (success ? "SUCCESS" : "FAILED") +
                             " | " + metrics.getDurationSeconds() + "s");
        }
    }

    private void checkPerformanceAlerts(ImportMetrics metrics) {
        double duration = metrics.getDurationSeconds();
        double triplesPerSec = metrics.getTriplesPerSecond();

        if (duration > VERY_SLOW_IMPORT_THRESHOLD) {
            System.err.println("[ALERT] Very slow import: " + metrics.projectId +
                             " has been running for " + duration + " seconds!");
        } else if (duration > SLOW_IMPORT_THRESHOLD) {
            System.out.println("[WARNING] Slow import: " + metrics.projectId +
                             " duration: " + duration + "s");
        }

        if (triplesPerSec > 0 && triplesPerSec < POOR_PERFORMANCE_THRESHOLD) {
            System.out.println("[WARNING] Poor performance: " + metrics.projectId +
                             " | " + String.format("%.0f", triplesPerSec) + " triples/sec");
        }
    }

    public OverallStats getOverallStats() {
        List<ImportMetrics> recentImports;
        synchronized (completedImports) {
            recentImports = new ArrayList<>(completedImports);
        }

        if (recentImports.isEmpty()) {
            return new OverallStats();
        }

        List<ImportMetrics> last20 = recentImports.stream()
            .filter(m -> m.success)
            .skip(Math.max(0, recentImports.size() - 20))
            .collect(Collectors.toList());

        if (last20.isEmpty()) {
            return new OverallStats();
        }

        double avgDuration = last20.stream()
            .mapToDouble(ImportMetrics::getDurationSeconds)
            .average()
            .orElse(0);

        double avgTriplesPerSec = last20.stream()
            .mapToDouble(ImportMetrics::getTriplesPerSecond)
            .filter(d -> d > 0)
            .average()
            .orElse(0);

        double avgFileSizeMB = last20.stream()
            .mapToDouble(m -> m.fileSizeBytes / (1024.0 * 1024.0))
            .average()
            .orElse(0);

        return new OverallStats(
            totalImports.get(),
            successfulImports.get(),
            failedImports.get(),
            activeImports.size(),
            avgDuration,
            avgTriplesPerSec,
            avgFileSizeMB
        );
    }

    public List<ImportMetrics> getActiveImports() {
        return new ArrayList<>(activeImports.values());
    }

    public List<ImportMetrics> getRecentImports(int count) {
        synchronized (completedImports) {
            return completedImports.stream()
                .skip(Math.max(0, completedImports.size() - count))
                .collect(Collectors.toList());
        }
    }

    public String exportPrometheusMetrics() {
        OverallStats stats = getOverallStats();

        StringBuilder sb = new StringBuilder();
        sb.append("# HELP ontology_imports_total Total number of ontology imports\n");
        sb.append("# TYPE ontology_imports_total counter\n");
        sb.append("ontology_imports_total ").append(stats.totalImports).append("\n\n");

        sb.append("# HELP ontology_imports_success Successful imports\n");
        sb.append("# TYPE ontology_imports_success counter\n");
        sb.append("ontology_imports_success ").append(stats.successfulImports).append("\n\n");

        sb.append("# HELP ontology_imports_failed Failed imports\n");
        sb.append("# TYPE ontology_imports_failed counter\n");
        sb.append("ontology_imports_failed ").append(stats.failedImports).append("\n\n");

        sb.append("# HELP ontology_imports_active Currently active imports\n");
        sb.append("# TYPE ontology_imports_active gauge\n");
        sb.append("ontology_imports_active ").append(stats.activeImports).append("\n\n");

        sb.append("# HELP ontology_import_duration_seconds Average import duration\n");
        sb.append("# TYPE ontology_import_duration_seconds gauge\n");
        sb.append("ontology_import_duration_seconds ")
          .append(String.format("%.2f", stats.avgDurationSeconds)).append("\n\n");

        sb.append("# HELP ontology_import_triples_per_second Average processing speed\n");
        sb.append("# TYPE ontology_import_triples_per_second gauge\n");
        sb.append("ontology_import_triples_per_second ")
          .append(String.format("%.0f", stats.avgTriplesPerSecond)).append("\n\n");

        return sb.toString();
    }

    public static class ImportMetrics {
        public final String projectId;
        public final long fileSizeBytes;
        public final String fileName;
        public final Instant startTime;

        private Instant endTime;
        private long processedTriples;
        private long totalTriples;
        private String currentPhase;
        private boolean success;
        private String error;

        public ImportMetrics(String projectId, long fileSizeBytes, String fileName) {
            this.projectId = projectId;
            this.fileSizeBytes = fileSizeBytes;
            this.fileName = fileName;
            this.startTime = Instant.now();
            this.currentPhase = "starting";
        }

        public void updateProgress(long processedTriples, String phase) {
            this.processedTriples = processedTriples;
            this.currentPhase = phase;
        }

        public void complete(boolean success, long totalTriples, String error) {
            this.endTime = Instant.now();
            this.success = success;
            this.totalTriples = totalTriples;
            this.error = error;
        }

        public double getDurationSeconds() {
            Instant end = endTime != null ? endTime : Instant.now();
            return Duration.between(startTime, end).toMillis() / 1000.0;
        }

        public double getTriplesPerSecond() {
            double duration = getDurationSeconds();
            return duration > 0 ? (totalTriples > 0 ? totalTriples : processedTriples) / duration : 0;
        }

        public double getFileSizeMB() {
            return fileSizeBytes / (1024.0 * 1024.0);
        }

        @Override
        public String toString() {
            return String.format(
                "Import{project=%s, file=%s (%.1f MB), duration=%.1fs, triples=%,d, " +
                "speed=%.0f t/s, phase=%s, status=%s}",
                projectId,
                fileName,
                getFileSizeMB(),
                getDurationSeconds(),
                totalTriples > 0 ? totalTriples : processedTriples,
                getTriplesPerSecond(),
                currentPhase,
                endTime != null ? (success ? "SUCCESS" : "FAILED") : "IN_PROGRESS"
            );
        }
    }

    public static class OverallStats {
        public final long totalImports;
        public final long successfulImports;
        public final long failedImports;
        public final int activeImports;
        public final double avgDurationSeconds;
        public final double avgTriplesPerSecond;
        public final double avgFileSizeMB;

        public OverallStats() {
            this(0, 0, 0, 0, 0, 0, 0);
        }

        public OverallStats(long totalImports, long successfulImports, long failedImports,
                          int activeImports, double avgDurationSeconds,
                          double avgTriplesPerSecond, double avgFileSizeMB) {
            this.totalImports = totalImports;
            this.successfulImports = successfulImports;
            this.failedImports = failedImports;
            this.activeImports = activeImports;
            this.avgDurationSeconds = avgDurationSeconds;
            this.avgTriplesPerSecond = avgTriplesPerSecond;
            this.avgFileSizeMB = avgFileSizeMB;
        }

        public double getSuccessRate() {
            return totalImports > 0 ?
                (successfulImports * 100.0) / totalImports : 0;
        }

        @Override
        public String toString() {
            return String.format(
                "Overall Stats:\n" +
                "  Total imports: %,d (%.1f%% success)\n" +
                "  Active: %d\n" +
                "  Avg duration: %.1f seconds\n" +
                "  Avg speed: %,.0f triples/second\n" +
                "  Avg file size: %.1f MB",
                totalImports,
                getSuccessRate(),
                activeImports,
                avgDurationSeconds,
                avgTriplesPerSecond,
                avgFileSizeMB
            );
        }
    }

    public static void main(String[] args) throws Exception {
        PerformanceMonitoring monitor = PerformanceMonitoring.getInstance();

        for (int i = 0; i < 5; i++) {
            String projectId = "project-" + i;
            long fileSize = (50 + i * 20) * 1024 * 1024L;

            monitor.startImport(projectId, fileSize, "ontology-" + i + ".owl");

            for (int j = 1; j <= 5; j++) {
                Thread.sleep(1000);
                monitor.updateProgress(projectId, j * 100000, "importing");
            }

            monitor.completeImport(projectId, true, 500000, null);
        }

        System.out.println("\n" + monitor.getOverallStats());

        System.out.println("\n=== Prometheus Metrics ===");
        System.out.println(monitor.exportPrometheusMetrics());
    }
}
