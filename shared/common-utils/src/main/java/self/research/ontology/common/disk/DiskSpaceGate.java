package self.research.ontology.common.disk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DiskSpaceGate {

    public static final String FRACTION_PROPERTY = "ontocode.disk.min-free-fraction";
    public static final String MULTIPLIER_PROPERTY = "ontocode.disk.multiplier";
    public static final double DEFAULT_MIN_FREE_FRACTION = 0.10;
    public static final double DEFAULT_MULTIPLIER = 1.0;

    private static final DiskSpaceGate DEFAULTS = fromEnvironment();

    private final double minFreeFraction;
    private final double multiplier;

    public DiskSpaceGate(double minFreeFraction, double multiplier) {
        this.minFreeFraction = Math.max(0.0, Math.min(0.95, minFreeFraction));
        this.multiplier = Math.max(1.0, multiplier);
    }

    public static DiskSpaceGate defaults() {
        return DEFAULTS;
    }

    public void requireFree(Path path, long neededBytes) {
        Path existing = nearestExisting(path);
        if (existing == null) {
            return;
        }
        long usable;
        long reserve;
        try {
            var store = Files.getFileStore(existing);
            usable = store.getUsableSpace();
            reserve = (long) Math.ceil(store.getTotalSpace() * minFreeFraction);
        } catch (IOException e) {
            return;
        }
        long needed = Math.max(0L, neededBytes);
        long scaled = (long) Math.min(Long.MAX_VALUE / 4.0, needed * multiplier);
        long required = scaled + reserve;
        if (usable < required) {
            throw new InsufficientDiskSpaceException(required, usable);
        }
    }

    private static Path nearestExisting(Path path) {
        Path current = path == null ? null : path.toAbsolutePath().normalize();
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current;
    }

    private static DiskSpaceGate fromEnvironment() {
        return new DiskSpaceGate(read(FRACTION_PROPERTY, "ONTOCODE_DISK_MIN_FREE_FRACTION", DEFAULT_MIN_FREE_FRACTION),
                read(MULTIPLIER_PROPERTY, "ONTOCODE_DISK_MULTIPLIER", DEFAULT_MULTIPLIER));
    }

    private static double read(String property, String env, double fallback) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(env);
        }
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
