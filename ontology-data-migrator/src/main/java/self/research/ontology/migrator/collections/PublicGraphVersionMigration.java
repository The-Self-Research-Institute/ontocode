package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class PublicGraphVersionMigration implements CollectionMigration {

    @Override
    public String name() {
        return "project_public_graph_versions";
    }

    @Override
    public String sourceCollection() {
        return "project_public_graph_versions";
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("project_public_graph_version", "project_id")
                .put("project_id", Values.idText(d.get("_id")))
                .put("version", Values.longValue(d, "version", 0L))
                .put("updated_at", Values.time(d, "updatedAt"))
                .build());
    }
}
