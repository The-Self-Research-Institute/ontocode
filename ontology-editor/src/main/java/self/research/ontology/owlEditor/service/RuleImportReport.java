package self.research.ontology.owlEditor.service;

import java.util.List;

public record RuleImportReport(int read, List<Imported> imported, List<Skipped> skipped, int duplicates, int invalid,
                               boolean dryRun, String previewStamp, boolean replayed, String changeSetId, boolean stale,
                               String message) {

    public static final String DUPLICATE = "duplicate";
    public static final String INVALID = "invalid";
    public static final String UNKNOWN_ENTITY = "unknown_entity";
    public static final String AMBIGUOUS_ENTITY = "ambiguous_entity";
    public static final String PREFIX_CONFLICT = "prefix_conflict";

    public record Imported(String id, String ruleName, int row) {
    }

    public record Skipped(String sheet, int row, String ruleName, String reason, String kind, List<String> suggestions) {
    }

    static RuleImportReport of(int read, List<Imported> imported, List<Skipped> skipped, boolean dryRun,
                               String previewStamp, String changeSetId) {
        int duplicates = (int) skipped.stream().filter(item -> DUPLICATE.equals(item.kind())).count();
        return new RuleImportReport(read, List.copyOf(imported), List.copyOf(skipped), duplicates,
                skipped.size() - duplicates, dryRun, previewStamp, false, changeSetId, false, null);
    }

    public RuleImportReport asReplay() {
        return new RuleImportReport(read, imported, skipped, duplicates, invalid, dryRun, previewStamp, true,
                changeSetId, false, null);
    }

    public RuleImportReport asStale(String reason) {
        return new RuleImportReport(read, imported, skipped, duplicates, invalid, dryRun, previewStamp, false,
                changeSetId, true, reason);
    }
}
