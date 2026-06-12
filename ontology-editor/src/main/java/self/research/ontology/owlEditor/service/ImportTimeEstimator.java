package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Estimates import duration for queue wait times.
 * Baseline: file-size tiers (~1–5 min for large files). When recent imports of
 * similar size exist, uses their actual bulk-load duration (no byte-rate extrapolation).
 */
@Service
public class ImportTimeEstimator {

    private static final long DEFAULT_ESTIMATED_DURATION_MS = 3 * 60 * 1000;
    /** Hard cap per file — large ontologies typically finish within a few minutes. */
    private static final long MAX_ESTIMATE_MS = 8 * 60 * 1000;
    private static final long MIN_ESTIMATE_MS = 20_000;
    private static final int MAX_SAMPLES = 20;

    private final Deque<ImportSample> samples = new ArrayDeque<>();

    public synchronized void recordSample(long fileSizeBytes,
                                          Integer classCount,
                                          Integer annotationCount,
                                          long durationMs) {
        if (durationMs <= 0 || fileSizeBytes <= 0) {
            return;
        }

        samples.addLast(new ImportSample(fileSizeBytes, durationMs));
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    public synchronized long estimateDurationMs(long fileSizeBytes,
                                                Integer classCount,
                                                Integer annotationCount) {
        long tierEstimate = tierEstimateMs(fileSizeBytes);
        long similarSampleMs = averageDurationForSimilarSize(fileSizeBytes);

        if (similarSampleMs > 0) {
            // Trust measured times for similar-sized files, but stay within tier bounds.
            long blended = Math.round((tierEstimate + similarSampleMs) / 2.0);
            return clamp(blended);
        }

        return clamp(tierEstimate);
    }

    public synchronized long getAverageDurationMs() {
        if (samples.isEmpty()) {
            return DEFAULT_ESTIMATED_DURATION_MS;
        }

        return clamp((long) samples.stream()
                .mapToLong(sample -> sample.durationMs)
                .average()
                .orElse(DEFAULT_ESTIMATED_DURATION_MS));
    }

    /**
     * Typical bulk-import duration by file size (observed: large OWL files ~1–5 min).
     */
    static long tierEstimateMs(long fileSizeBytes) {
        if (fileSizeBytes <= 0) {
            return DEFAULT_ESTIMATED_DURATION_MS;
        }
        long mb = fileSizeBytes / (1024 * 1024);
        if (mb < 1) {
            return 45_000;
        }
        if (mb < 10) {
            return 90_000;
        }
        if (mb < 50) {
            return 2 * 60_000;
        }
        if (mb < 150) {
            return 3 * 60_000;
        }
        if (mb < 300) {
            return 4 * 60_000;
        }
        return 5 * 60_000;
    }

    private long averageDurationForSimilarSize(long targetFileSizeBytes) {
        return (long) samples.stream()
                .filter(sample -> isSimilarSize(sample.fileSizeBytes, targetFileSizeBytes))
                .mapToLong(sample -> sample.durationMs)
                .average()
                .orElse(0);
    }

    private static boolean isSimilarSize(long sampleBytes, long targetBytes) {
        long min = Math.min(sampleBytes, targetBytes);
        long max = Math.max(sampleBytes, targetBytes);
        return max <= min * 4L;
    }

    private static long clamp(long ms) {
        return Math.max(MIN_ESTIMATE_MS, Math.min(MAX_ESTIMATE_MS, ms));
    }

    private static final class ImportSample {
        private final long fileSizeBytes;
        private final long durationMs;

        private ImportSample(long fileSizeBytes, long durationMs) {
            this.fileSizeBytes = Math.max(0, fileSizeBytes);
            this.durationMs = durationMs;
        }
    }
}
