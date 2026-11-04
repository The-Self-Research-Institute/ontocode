package self.research.ontology.owlEditor.migration;

import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

@ChangeUnit(id = "project-user-ids", order = "001", author = "ontocode")
public class V001ProjectUserIds {

    private static final Logger log = LoggerFactory.getLogger(V001ProjectUserIds.class);

    @Execution
    public void execute(MongoTemplate mongoTemplate) {
        int updated = ProjectUserIdBackfill.run(mongoTemplate);
        log.info("[MIGRATION] Added user ids to {} project(s)", updated);
    }

    @RollbackExecution
    public void rollback() {
    }
}
