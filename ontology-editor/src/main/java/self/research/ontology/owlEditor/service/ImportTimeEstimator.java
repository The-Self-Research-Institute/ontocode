package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Simple estimator that learns average per-feature processing time
 * from recent imports and produces an ETA for queued files.
 */
@Service
public class ImportTimeEstimator {

    private static final long DEFAULT_ESTIMATED_DURATION_MS = 5 * 60 * 1000;
    private static final int MAX_SAMPLES = 20;

    private final Deque<ImportSample> samples = new ArrayDeque<>();

    public synchronized void recordSample(long fileSizeBytes,
                                          Integer classCount,
                                          Integer annotationCount,
                                          long durationMs) {
        if (durationMs <= 0) {
            return;
        }

        samples.addLast(new ImportSample(fileSizeBytes, safeCount(classCount), safeCount(annotationCount), durationMs));
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    public synchronized long estimateDurationMs(long fileSizeBytes,
                                                Integer classCount,
                                                Integer annotationCount) {
        long fallback = getAverageDurationMs();

        List<Long> estimates = new ArrayList<>(3);

        double msPerByte = averageMsPerByte();
        if (msPerByte > 0 && fileSizeBytes > 0) {
            estimates.add(Math.round(msPerByte * fileSizeBytes));
        }

        double msPerClass = averageMsPerClass();
        long classCountValue = safeCount(classCount);
        if (msPerClass > 0 && classCountValue > 0) {
            estimates.add(Math.round(msPerClass * classCountValue));
        }

        double msPerAnnotation = averageMsPerAnnotation();
        long annotationCountValue = safeCount(annotationCount);
        if (msPerAnnotation > 0 && annotationCountValue > 0) {
            estimates.add(Math.round(msPerAnnotation * annotationCountValue));
        }

        if (estimates.isEmpty()) {
            return fallback;
        }

        long average = (long) estimates.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(fallback);

        return average > 0 ? average : fallback;
    }

    public synchronized long getAverageDurationMs() {
        if (samples.isEmpty()) {
            return DEFAULT_ESTIMATED_DURATION_MS;
        }

        return (long) samples.stream()
                .mapToLong(sample -> sample.durationMs)
                .average()
                .orElse(DEFAULT_ESTIMATED_DURATION_MS);
    }

    private double averageMsPerByte() {
        double total = 0;
        int count = 0;
        for (ImportSample sample : samples) {
            if (sample.fileSizeBytes > 0) {
                total += (double) sample.durationMs / sample.fileSizeBytes;
                count++;
            }
        }
        return count == 0 ? 0 : total / count;
    }

    private double averageMsPerClass() {
        double total = 0;
        int count = 0;
        for (ImportSample sample : samples) {
            if (sample.classCount > 0) {
                total += (double) sample.durationMs / sample.classCount;
                count++;
            }
        }
        return count == 0 ? 0 : total / count;
    }

    private double averageMsPerAnnotation() {
        double total = 0;
        int count = 0;
        for (ImportSample sample : samples) {
            if (sample.annotationCount > 0) {
                total += (double) sample.durationMs / sample.annotationCount;
                count++;
            }
        }
        return count == 0 ? 0 : total / count;
    }

    private long safeCount(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static final class ImportSample {
        private final long fileSizeBytes;
        private final long classCount;
        private final long annotationCount;
        private final long durationMs;

        private ImportSample(long fileSizeBytes, long classCount, long annotationCount, long durationMs) {
            this.fileSizeBytes = Math.max(0, fileSizeBytes);
            this.classCount = Math.max(0, classCount);
            this.annotationCount = Math.max(0, annotationCount);
            this.durationMs = durationMs;
        }
    }
}
