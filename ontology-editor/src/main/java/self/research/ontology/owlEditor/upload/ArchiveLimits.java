package self.research.ontology.owlEditor.upload;

public record ArchiveLimits(long gzipMaxBytes, long gzipMaxRatio, int zipMaxEntries,
                            long zipMaxTotalBytes, long zipMaxEntryBytes) {

    public static final long RATIO_FLOOR_BYTES = 32L * 1024 * 1024;

    public static ArchiveLimits defaults() {
        long gib = 1024L * 1024 * 1024;
        return new ArchiveLimits(4 * gib, 200, 5000, 4 * gib, 2 * gib);
    }
}
