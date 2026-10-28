package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.UUID;

public record ChangeOrigin(String changeSetId, String source, HistoryChange.AiInfo ai, String revertsChangeSetId,
                           String rollbackAuditId, ExactCarrier exact, String toolSource, String toolLabel,
                           RuleNote ruleNote, java.util.Map<String, String> batch) {

    public record RuleNote(String name, String action, String text) {
        public static final String ADDED = "added";
        public static final String CHANGED = "changed";
        public static final String TURNED_OFF = "turned off";
        public static final String TURNED_ON = "turned on";
        public static final String DELETED = "deleted";
        public static final String MIGRATED = "migrated";
        public static final String RENAMED = "renamed";
        public static final String IMPORTED = "imported";

        public RuleNote(String name, String action) {
            this(name, action, null);
        }

        public String description() {
            if (text != null) {
                return text;
            }
            return MIGRATED.equals(action) ? "Migrated saved rule \"" + name + "\"" : "Rule \"" + name + "\" " + action;
        }
    }

    public ChangeOrigin(String changeSetId, String source, HistoryChange.AiInfo ai, String revertsChangeSetId,
                        String rollbackAuditId, ExactCarrier exact, String toolSource, String toolLabel,
                        RuleNote ruleNote) {
        this(changeSetId, source, ai, revertsChangeSetId, rollbackAuditId, exact, toolSource, toolLabel, ruleNote, null);
    }

    public ChangeOrigin(String changeSetId, String source, HistoryChange.AiInfo ai, String revertsChangeSetId,
                        String rollbackAuditId, ExactCarrier exact, String toolSource, String toolLabel) {
        this(changeSetId, source, ai, revertsChangeSetId, rollbackAuditId, exact, toolSource, toolLabel, null);
    }

    public ChangeOrigin(String changeSetId, String source, HistoryChange.AiInfo ai, String revertsChangeSetId,
                        String rollbackAuditId, ExactCarrier exact) {
        this(changeSetId, source, ai, revertsChangeSetId, rollbackAuditId, exact, null, null);
    }

    public ChangeOrigin(String changeSetId, String source, HistoryChange.AiInfo ai, String revertsChangeSetId,
                        String rollbackAuditId) {
        this(changeSetId, source, ai, revertsChangeSetId, rollbackAuditId, null, null, null);
    }

    public ChangeOrigin withExact(ExactCarrier carrier) {
        return new ChangeOrigin(changeSetId, source, ai, revertsChangeSetId, rollbackAuditId, carrier, toolSource,
                toolLabel, ruleNote, batch);
    }

    public ChangeOrigin withRule(RuleNote note) {
        return new ChangeOrigin(changeSetId, source, ai, revertsChangeSetId, rollbackAuditId, exact, toolSource,
                toolLabel, note, batch);
    }

    public ChangeOrigin withBatch(java.util.Map<String, String> entries) {
        return new ChangeOrigin(changeSetId, source, ai, revertsChangeSetId, rollbackAuditId, exact, toolSource,
                toolLabel, ruleNote, entries);
    }

    public static final String MANUAL = "MANUAL";
    public static final String TOOL = "TOOL";
    private static final int MAX_LABEL_CHARS = 300;
    private static final java.util.regex.Pattern TOOL_SOURCE = java.util.regex.Pattern.compile("^[a-z0-9-]{1,40}$");
    public static final String AI = "AI";
    public static final String ROLLBACK = "ROLLBACK";

    private static final int MAX_SUMMARY_CHARS = 200;

    public static ChangeOrigin manual() {
        return new ChangeOrigin(UUID.randomUUID().toString(), MANUAL, null, null, null);
    }

    public static ChangeOrigin fromTool(String toolSource, String toolLabel) {
        if (toolSource == null || !TOOL_SOURCE.matcher(toolSource).matches()) {
            return manual();
        }
        return new ChangeOrigin(UUID.randomUUID().toString(), TOOL, null, null, null, null, toolSource,
                sanitizeLabel(toolLabel));
    }

    static String sanitizeLabel(String label) {
        if (label == null) {
            return null;
        }
        String cleaned = label.replaceAll("\\p{Cc}", "").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() <= MAX_LABEL_CHARS ? cleaned : cleaned.substring(0, MAX_LABEL_CHARS);
    }

    public static ChangeOrigin ai(String groupId, String provider, String model, String sessionId, String summary) {
        return new ChangeOrigin(groupId, AI, new HistoryChange.AiInfo(provider, model, sessionId, groupId, trim(summary)), null,
                null);
    }

    public static ChangeOrigin rollback(String revertsChangeSetId, String rollbackAuditId) {
        return new ChangeOrigin(UUID.randomUUID().toString(), ROLLBACK, null, revertsChangeSetId, rollbackAuditId);
    }

    private static String trim(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        String oneLine = summary.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= MAX_SUMMARY_CHARS ? oneLine : oneLine.substring(0, MAX_SUMMARY_CHARS - 1) + "…";
    }
}
