package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class SparqlQueryMigration implements CollectionMigration {

    @Override
    public String name() {
        return "sparql_queries";
    }

    @Override
    public String sourceCollection() {
        return "sparql_queries";
    }

    @Override
    public Skip skipReason(Document d) {
        return EditorSkips.missingProject(d);
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("sparql_query", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.required(d, "projectId"))
                .put("name", Values.text(d, "name"))
                .put("query_text", Values.text(d, "queryText"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .build());
    }
}
