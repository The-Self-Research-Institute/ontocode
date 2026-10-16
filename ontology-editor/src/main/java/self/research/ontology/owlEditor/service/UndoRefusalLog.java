package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import self.research.ontology.owlEditor.service.UserUndoService.Identity;

@Slf4j
public final class UndoRefusalLog {

    private UndoRefusalLog() {
    }

    public static void record(String projectId, Identity who, String direction, String targetChangeId, String reason) {
        log.info("[Undo] Refused {} in project {} for {} (target {}): {}", direction, projectId,
                who == null ? "unknown user" : who.aliases(), targetChangeId == null ? "none" : targetChangeId, reason);
    }
}
