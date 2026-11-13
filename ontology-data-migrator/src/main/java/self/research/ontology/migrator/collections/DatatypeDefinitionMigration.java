package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class DatatypeDefinitionMigration implements CollectionMigration {

    @Override
    public String name() {
        return "datatype_definitions";
    }

    @Override
    public String sourceCollection() {
        return "datatype_definitions";
    }

    @Override
    public Skip skipReason(Document d) {
        return EditorSkips.missingProject(d);
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("datatype_definition", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.required(d, "projectId"))
                .put("datatype_iri", Values.text(d, "datatypeIri"))
                .put("expression", Values.text(d, "expression"))
                .put("definition_type", Values.text(d, "definitionType"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .put("draft", Values.bool(d, "draft", false))
                .put("owner_user_id", Values.text(d, "ownerUserId"))
                .put("replaces_id", Values.text(d, "replacesId"))
                .put("deleted", Values.bool(d, "deleted", false))
                .build());
    }
}
