package self.research.ontology.owlEditor.service;

import org.springframework.context.annotation.Lazy;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;

@Component
public class HistoryDraftSessionStamper implements BeforeConvertCallback<HistoryChange> {

    private final DraftSessionRepository sessions;

    public HistoryDraftSessionStamper(@Lazy DraftSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Override
    public HistoryChange onBeforeConvert(HistoryChange change, String collection) {
        if (change.isDraft() && change.getDraftSessionId() == null
                && change.getProjectId() != null && change.getUserId() != null) {
            try {
                sessions.findByProjectIdAndUserId(change.getProjectId(), change.getUserId())
                        .ifPresent(session -> change.setDraftSessionId(session.getId()));
            } catch (RuntimeException ignored) {
                // history must still be saved when the session lookup fails
            }
        }
        return change;
    }
}
