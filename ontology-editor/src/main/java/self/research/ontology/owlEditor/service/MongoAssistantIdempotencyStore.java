package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.document.AssistantIdempotencyRecordDocument;
import self.research.ontology.owlEditor.document.AssistantIdempotencyRecordDocument.State;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
public class MongoAssistantIdempotencyStore implements AssistantIdempotencyStore {

    private final MongoTemplate mongoTemplate;

    public MongoAssistantIdempotencyStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public boolean tryInsert(AssistantIdempotencyRecordDocument inFlight) {
        try {
            mongoTemplate.insert(inFlight);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<AssistantIdempotencyRecordDocument> find(String id) {
        return Optional.ofNullable(mongoTemplate.findById(id, AssistantIdempotencyRecordDocument.class));
    }

    @Override
    public boolean tryTakeOver(String id, Instant staleInFlightBefore, Instant now,
                               AssistantIdempotencyRecordDocument replacement) {
        Query query = Query.query(Criteria.where("_id").is(id).orOperator(
                Criteria.where("expiresAt").lt(now),
                Criteria.where("state").is(State.IN_FLIGHT).and("updatedAt").lt(staleInFlightBefore)));
        AssistantIdempotencyRecordDocument previous = mongoTemplate.findAndReplace(
                query, replacement, FindAndReplaceOptions.none());
        return previous != null;
    }

    @Override
    public boolean complete(String id, String ownerToken, int status, String contentType, String body, Instant now) {
        Query query = Query.query(Criteria.where("_id").is(id)
                .and("ownerToken").is(ownerToken)
                .and("state").is(State.IN_FLIGHT));
        Update update = new Update()
                .set("state", State.COMPLETED)
                .set("responseStatus", status)
                .set("responseContentType", contentType)
                .set("responseBody", body)
                .set("updatedAt", now);
        boolean stored = mongoTemplate.updateFirst(query, update, AssistantIdempotencyRecordDocument.class)
                .getModifiedCount() > 0;
        if (!stored) {
            log.warn("[AssistantIdempotency] Record {} was taken over before its response could be stored", id);
        }
        return stored;
    }

    @Override
    public void abandon(String id, String ownerToken) {
        mongoTemplate.remove(Query.query(Criteria.where("_id").is(id)
                .and("ownerToken").is(ownerToken)
                .and("state").is(State.IN_FLIGHT)), AssistantIdempotencyRecordDocument.class);
    }
}
