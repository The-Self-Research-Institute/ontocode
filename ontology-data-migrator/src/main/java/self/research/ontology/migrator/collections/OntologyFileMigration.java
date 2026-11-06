package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class OntologyFileMigration implements CollectionMigration {

    @Override
    public String name() {
        return "projects:files";
    }

    @Override
    public String sourceCollection() {
        return "projects";
    }

    @Override
    public boolean accepts(Document d) {
        return ProjectKinds.isFileDocument(d);
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("ontology_file", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("name", Values.text(d, "name"))
                .put("file_name", Values.text(d, "filename"))
                .put("owner_email", Values.text(d, "ownerEmail"))
                .put("owner_id", Values.text(d, "ownerId"))
                .put("project_id", Values.text(d, "projectId"))
                .put("workspace_id", Values.text(d, "workspaceId"))
                .put("blob_id", Values.text(d, "gridfsFileId"))
                .put("status", Values.text(d, "status"))
                .put("status_message", Values.text(d, "statusMessage"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .put("metadata", Values.json(d.get("metadata")))
                .put("mutation_version", Values.longValue(d, "mutationVersion"))
                .put("main_graph_revision", Values.longValue(d, "mainGraphRevision"))
                .build());
    }
}
