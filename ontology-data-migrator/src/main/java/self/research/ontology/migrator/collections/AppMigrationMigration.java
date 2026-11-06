package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AppMigrationMigration implements CollectionMigration {

    @Override
    public String name() {
        return "app_migrations";
    }

    @Override
    public String sourceCollection() {
        return "app_migrations";
    }

    @Override
    public List<Write> map(Document d) {
        Map<String, Object> details = new LinkedHashMap<>(d);
        details.remove("_id");
        details.remove("completedAt");
        details.remove("_class");
        return List.of(Write.upsert("app_migration", "id")
                .put("id", Values.idText(d.get("_id")))
                .putIfPresent("completed_at", Values.time(d, "completedAt"))
                .put("details", Values.json(details))
                .build());
    }
}
