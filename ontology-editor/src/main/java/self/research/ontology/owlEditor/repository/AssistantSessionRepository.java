package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;

import java.time.Instant;
import java.util.Optional;

public interface AssistantSessionRepository {

    AssistantSessionDocument save(AssistantSessionDocument session);

    Optional<AssistantSessionDocument> findById(String id);

    Optional<AssistantSessionDocument> findByIdAndUserEmail(String id, String userEmail);

    long countActive(String userEmail, Instant now);

    Optional<Instant> soonestActiveDeadline(String userEmail, Instant now);

    boolean tryConsumeRetrievalAttempt(String id, Instant now);

    boolean tryConsumeTokenBudget(String id, int tokens, Instant now);

    void updateStatus(String id, AssistantSessionStatus status, Instant now);
}
