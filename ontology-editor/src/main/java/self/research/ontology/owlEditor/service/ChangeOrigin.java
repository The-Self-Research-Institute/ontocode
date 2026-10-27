package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.UUID;

public record ChangeOrigin(String changeSetId, String source, HistoryChange.AiInfo ai, String revertsChangeSetId,
                           String rollbackAuditId, ExactCarrier exact, String toolSource, String toolLabel) {

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
                toolLabel);
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
