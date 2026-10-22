package self.research.ontology.owlEditor.service;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.HistoryChange;

@Service
public class ExactCoverService {

    private final MongoTemplate mongoTemplate;

    public ExactCoverService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void record(String projectId, ExactCarrier carrier) {
        if (carrier.carrierEditId() == null || carrier.covers() == 0) {
            return;
        }
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("projectId").is(projectId).and("editId").is(carrier.carrierEditId())),
                new Update().set("exactCovers", carrier.covers()),
                HistoryChange.class);
    }
}
