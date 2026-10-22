package self.research.ontology.owlEditor.service;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class DocumentRollbackActions implements RollbackActionRunner {

    private final MongoTemplate mongoTemplate;
    private final String swrlServiceUrl;
    private final RestTemplate restTemplate;

    public DocumentRollbackActions(MongoTemplate mongoTemplate,
                                   @Value("${ontocode.swrl.service.url:http://localhost:8084}") String swrlServiceUrl) {
        this.mongoTemplate = mongoTemplate;
        this.swrlServiceUrl = swrlServiceUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(4_000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public void run(String projectId, RollbackAction action, boolean draft, String draftOwner) {
        DocumentStore store = requireStore(action);
        Object id = identity(action);
        if (action.document() != null && !projectId.equals(Document.parse(action.document()).getString("projectId"))) {
            throw new IllegalArgumentException("The stored document does not belong to this project");
        }
        var collection = mongoTemplate.getCollection(store.collection());
        if (action.document() == null) {
            collection.deleteOne(Filters.eq("_id", id));
        } else {
            collection.replaceOne(Filters.eq("_id", id), Document.parse(action.document()), new ReplaceOptions().upsert(true));
        }
        if (store == DocumentStore.SWRL_RULE) {
            clearSwrlCache(projectId);
        }
    }

    @Override
    public boolean holds(String projectId, RollbackAction action, boolean draft, String draftOwner) {
        DocumentStore store = DocumentStore.find(action.store());
        if (store == null) {
            return false;
        }
        Document current = mongoTemplate.getCollection(store.collection()).find(Filters.eq("_id", identity(action))).first();
        if (action.expectedDocument() == null) {
            return current == null;
        }
        if (current == null) {
            return false;
        }
        Document expected = Document.parse(action.expectedDocument());
        store.volatileFields().forEach(field -> {
            current.remove(field);
            expected.remove(field);
        });
        return current.equals(expected);
    }

    private static DocumentStore requireStore(RollbackAction action) {
        DocumentStore store = DocumentStore.find(action.store());
        if (store == null) {
            throw new IllegalArgumentException("Unknown document store " + action.store());
        }
        return store;
    }

    private static Object identity(RollbackAction action) {
        String json = action.document() != null ? action.document() : action.expectedDocument();
        if (json == null) {
            throw new IllegalArgumentException("A document action needs a before or after state");
        }
        return Document.parse(json).get("_id");
    }

    private void clearSwrlCache(String projectId) {
        try {
            restTemplate.postForEntity(swrlServiceUrl + "/api/swrl/" + projectId + "/cache/clear", null, String.class);
        } catch (RuntimeException e) {
            log.warn("[UNDO] Could not clear the SWRL engine cache for {}: {}", projectId, e.getMessage());
        }
    }
}
