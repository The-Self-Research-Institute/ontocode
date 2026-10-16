package self.research.ontology.owlEditor.service;

import java.util.Locale;
import java.util.Map;

public enum Limit {

    WHOLE_GRAPH_MAX_TRIPLES(Group.HISTORY, "triples", 1_000, 20_000, 60_000, 150_000),
    MAX_DELTA_TRIPLES(Group.HISTORY, "triples", 100, 2_000, 5_000, 20_000),
    MERGE_DELTA_MAX_TRIPLES(Group.HISTORY, "triples", 1_000, 20_000, 200_000, 500_000),
    DELTA_BATCH_TRIPLES(Group.HISTORY, "triples", 100, 1_000, 5_000, 10_000),
    NEIGHBOURHOOD_DEPTH(Group.HISTORY, "levels", 2, 6, 10, 14),
    CANONICAL_ROUNDS(Group.HISTORY, "rounds", 2, 6, 8, 10),
    FEED_PAGE_SIZE(Group.HISTORY, "rows", 10, 100, 200, 400),
    FEED_MAX_PAGES(Group.HISTORY, "pages", 1, 10, 25, 50),
    FEED_MAX_COUNT(Group.HISTORY, "rows", 10, 200, 500, 1_000),
    UNDO_LOOKBACK(Group.HISTORY, "rows", 5, 50, 100, 200),
    DEPENDENT_SCAN(Group.HISTORY, "rows", 10, 100, 200, 400),
    REDO_AUDIT_LOOKBACK(Group.HISTORY, "rows", 1, 10, 20, 40),
    MAX_DEPENDENTS_SHOWN(Group.HISTORY, "rows", 1, 5, 5, 10),
    INTENT_STALE_MINUTES(Group.HISTORY, "minutes", 1, 2, 2, 5),

    OWL_PARSING_CORE_THREADS(Group.THREADS, "threads", 1, 2, 4, 8),
    OWL_PARSING_MAX_THREADS(Group.THREADS, "threads", 1, 4, 8, 16),
    OWL_PARSING_QUEUE(Group.THREADS, "tasks", 1, 100, 200, 400),
    HIERARCHY_INDEX_CORE_THREADS(Group.THREADS, "threads", 1, 1, 1, 2),
    HIERARCHY_INDEX_MAX_THREADS(Group.THREADS, "threads", 1, 1, 2, 4),
    HIERARCHY_INDEX_QUEUE(Group.THREADS, "tasks", 1, 50, 100, 200),
    DL_QUERY_CORE_THREADS(Group.THREADS, "threads", 1, 1, 1, 2),
    DL_QUERY_MAX_THREADS(Group.THREADS, "threads", 1, 1, 2, 4),
    DL_QUERY_QUEUE(Group.THREADS, "tasks", 1, 25, 50, 100),
    DESKTOP_MODEL_CORE_THREADS(Group.THREADS, "threads", 1, 1, 1, 2),
    DESKTOP_MODEL_MAX_THREADS(Group.THREADS, "threads", 1, 1, 2, 4),
    DESKTOP_MODEL_QUEUE(Group.THREADS, "tasks", 1, 25, 50, 100),
    DESKTOP_WARM_THREADS(Group.THREADS, "threads", 1, 1, 1, 2),
    DESKTOP_WARM_QUEUE(Group.THREADS, "tasks", 1, 5, 10, 20),
    DRAFT_COPY_CORE_THREADS(Group.THREADS, "threads", 1, 1, 1, 2),
    DRAFT_COPY_MAX_THREADS(Group.THREADS, "threads", 1, 1, 2, 4),
    DRAFT_COPY_QUEUE(Group.THREADS, "tasks", 1, 10, 20, 40),
    METADATA_CORE_THREADS(Group.THREADS, "threads", 1, 1, 2, 4),
    METADATA_MAX_THREADS(Group.THREADS, "threads", 1, 2, 4, 8),
    METADATA_QUEUE(Group.THREADS, "tasks", 1, 50, 100, 200),
    SPARQL_CORE_THREADS(Group.THREADS, "threads", 1, 1, 2, 4),
    SPARQL_MAX_THREADS(Group.THREADS, "threads", 1, 2, 4, 8),
    SPARQL_QUEUE(Group.THREADS, "tasks", 1, 50, 100, 200),

    IMPORT_MAX_RETRIES(Group.IMPORT_EXPORT, "retries", 0, 2, 3, 5),
    IMPORT_MIN_PROCESSING_TIMEOUT_MINUTES(Group.IMPORT_EXPORT, "minutes", 1, 10, 10, 20),
    IMPORT_PROCESSING_TIMEOUT_PERCENT(Group.IMPORT_EXPORT, "percent of estimate", 100, 200, 200, 300),
    IMPORT_MAX_WAIT_MINUTES(Group.IMPORT_EXPORT, "minutes", 1, 10, 20, 40),
    DL_QUERY_MAX_WAIT_MINUTES(Group.IMPORT_EXPORT, "minutes", 1, 15, 30, 60),
    IMPORT_MAX_DEPTH(Group.IMPORT_EXPORT, "levels", 1, 2, 3, 5),
    IMPORT_MAX_TOTAL(Group.IMPORT_EXPORT, "imports", 1, 10, 20, 50),
    MANUAL_IMPORT_MAX_MB(Group.IMPORT_EXPORT, "MB", 1, 5, 10, 50),
    MANUAL_IMPORT_MAX_TRIPLES(Group.IMPORT_EXPORT, "triples", 1_000, 25_000, 50_000, 250_000),
    EXPORT_STUCK_JOB_MINUTES(Group.IMPORT_EXPORT, "minutes", 1, 45, 45, 90),

    CODE_VIEW_LINE_INDEX_FILES(Group.CODE_VIEW, "files", 16, 512, 2_048, 8_192),
    CODE_VIEW_SUBJECT_INDEX_FILES(Group.CODE_VIEW, "files", 16, 128, 512, 2_048),
    CODE_VIEW_REGION_LINES(Group.CODE_VIEW, "lines", 1_000, 100_000, 200_000, 500_000),
    PATCH_MAX_LINES(Group.CODE_VIEW, "lines", 1_000, 100_000, 250_000, 750_000),
    PATCH_MAX_CHANGED_SUBJECTS(Group.CODE_VIEW, "subjects", 10, 1_000, 2_000, 6_000),
    RENAME_SCAN_MAX_LINES(Group.CODE_VIEW, "lines", 100, 5_000, 10_000, 40_000),
    RENAME_SCAN_MAX_MB(Group.CODE_VIEW, "MB", 1, 4, 8, 32),
    PREFIX_SCAN_MAX_LINES(Group.CODE_VIEW, "lines", 100, 5_000, 5_000, 20_000),
    CODE_VIEW_BULK_DIFF_THRESHOLD(Group.CODE_VIEW, "changed subjects", 1, 60, 60, 200),

    REASONER_ELK_ABOVE_AXIOMS(Group.REASONING, "axioms", 100, 5_000, 10_000, 50_000),
    REASONER_STRUCTURAL_ABOVE_AXIOMS(Group.REASONING, "axioms", 1_000, 50_000, 100_000, 500_000),
    DL_QUERY_TIMEOUT_SECONDS(Group.REASONING, "seconds", 1, 10, 10, 30),
    QUERY_MAX_DESCENDANTS(Group.REASONING, "classes", 100, 2_000, 5_000, 20_000),
    HIERARCHY_TOP_LEVEL_STORE_LIMIT(Group.REASONING, "classes", 100, 20_000, 50_000, 200_000);

    public enum Group { HISTORY, THREADS, IMPORT_EXPORT, CODE_VIEW, REASONING }

    public enum Profile { SMALL, STANDARD, LARGE }

    private static final Limit[] ALL = values();
    private static volatile int[] current = presetValues(Profile.STANDARD);

    private final Group group;
    private final String unit;
    private final int floor;
    private final int[] presets;

    Limit(Group group, String unit, int floor, int small, int standard, int large) {
        this.group = group;
        this.unit = unit;
        this.floor = floor;
        this.presets = new int[] {small, standard, large};
    }

    public int value() {
        return current[ordinal()];
    }

    public long mb() {
        return value() * 1024L * 1024L;
    }

    public Group group() {
        return group;
    }

    public String unit() {
        return unit;
    }

    public int floor() {
        return floor;
    }

    public int preset(Profile profile) {
        return presets[profile.ordinal()];
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static Profile profileFor(long maxHeapBytes) {
        long gigabytes = maxHeapBytes / (1024L * 1024L * 1024L);
        if (gigabytes < 1) {
            return Profile.SMALL;
        }
        return gigabytes >= 6 ? Profile.LARGE : Profile.STANDARD;
    }

    public static Profile parseProfile(String text, long maxHeapBytes) {
        if (text == null || text.isBlank() || "auto".equalsIgnoreCase(text.trim())) {
            return profileFor(maxHeapBytes);
        }
        try {
            return Profile.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return profileFor(maxHeapBytes);
        }
    }

    public static int[] resolve(Profile profile, Map<Limit, Integer> overrides) {
        int[] values = presetValues(profile);
        for (Limit limit : ALL) {
            Integer override = overrides.get(limit);
            if (override != null) {
                values[limit.ordinal()] = Math.max(limit.floor, override);
            }
        }
        return values;
    }

    public static void install(int[] values) {
        current = values.clone();
    }

    public static Limit find(String name) {
        String wanted = normalise(name);
        for (Limit limit : ALL) {
            if (normalise(limit.name()).equals(wanted)) {
                return limit;
            }
        }
        return null;
    }

    private static String normalise(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static int[] presetValues(Profile profile) {
        int[] values = new int[ALL.length];
        for (Limit limit : ALL) {
            values[limit.ordinal()] = limit.preset(profile);
        }
        return values;
    }
}
