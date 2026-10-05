package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.model.HistoryChange;

import java.util.UUID;

public record ChangeOrigin(String changeSetId, String source, HistoryChange.AiInfo ai, String revertsChangeSetId,
                           String rollbackAuditId) {

    public static final String MANUAL = "MANUAL";
    public static final String AI = "AI";
    public static final String ROLLBACK = "ROLLBACK";

    private static final int MAX_SUMMARY_CHARS = 200;

    public static ChangeOrigin manual() {
        return new ChangeOrigin(UUID.randomUUID().toString(), MANUAL, null, null, null);
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
