package self.research.ontology.owlEditor.service;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.PublicGraphVersionDocument;

import java.time.Instant;

@Service
public class PublicGraphVersionStore {

    private final MongoTemplate mongoTemplate;

    public PublicGraphVersionStore(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public long increment(String projectId) {
        Update update = new Update().inc("version", 1L).set("updatedAt", Instant.now());
        PublicGraphVersionDocument updated = mongoTemplate.findAndModify(
                byId(projectId), update,
                FindAndModifyOptions.options().upsert(true).returnNew(true),
                PublicGraphVersionDocument.class);
        if (updated == null || updated.getVersion() == null) {
            throw new IllegalStateException("Public graph version increment returned no document for " + projectId);
        }
        return updated.getVersion();
    }

    public long read(String projectId) {
        PublicGraphVersionDocument doc = mongoTemplate.findOne(byId(projectId), PublicGraphVersionDocument.class);
        return doc != null && doc.getVersion() != null ? doc.getVersion() : 0L;
    }

    private static Query byId(String projectId) {
        return new Query(Criteria.where("_id").is(projectId));
    }
}
