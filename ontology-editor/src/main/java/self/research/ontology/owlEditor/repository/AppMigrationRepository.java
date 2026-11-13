package self.research.ontology.owlEditor.repository;

import java.util.Map;

public interface AppMigrationRepository {

    boolean isDone(String id);

    void markDone(String id, Map<String, Object> details);
}
