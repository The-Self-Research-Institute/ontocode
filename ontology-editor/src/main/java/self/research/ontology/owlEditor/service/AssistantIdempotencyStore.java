package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.document.AssistantIdempotencyRecordDocument;

import java.time.Instant;
import java.util.Optional;

public interface AssistantIdempotencyStore {

    boolean tryInsert(AssistantIdempotencyRecordDocument inFlight);

    Optional<AssistantIdempotencyRecordDocument> find(String id);

    boolean tryTakeOver(String id, Instant staleInFlightBefore, Instant now,
                        AssistantIdempotencyRecordDocument replacement);

    boolean complete(String id, String ownerToken, int status, String contentType, String body, Instant now);

    void abandon(String id, String ownerToken);
}
