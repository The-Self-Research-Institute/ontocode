package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.UniqueRule;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class FileMetadataMigration implements CollectionMigration {

    @Override
    public String name() {
        return "file_metadata";
    }

    @Override
    public String sourceCollection() {
        return "file_metadata";
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.field("file_id", "fileId"));
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("file_metadata", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("file_id", Values.required(d, "fileId"))
                .put("file_name", Values.text(d, "fileName"))
                .put("project_id", Values.text(d, "projectId"))
                .put("workspace_id", Values.text(d, "workspaceId"))
                .put("file_size", Values.longValue(d, "fileSize"))
                .put("file_type", Values.text(d, "fileType"))
                .put("extension", Values.text(d, "extension"))
                .put("blob_id", Values.text(d, "gridfsId"))
                .put("storage_location", Values.text(d, "storageLocation"))
                .put("uploaded_by", Values.text(d, "uploadedBy"))
                .put("uploader_email", Values.text(d, "uploaderEmail"))
                .put("uploader_username", Values.text(d, "uploaderUsername"))
                .put("uploaded_at", Values.time(d, "uploadedAt"))
                .put("status", Values.text(d, "status"))
                .put("is_deleted", Values.bool(d, "isDeleted", false))
                .put("deleted_at", Values.time(d, "deletedAt"))
                .put("deleted_by", Values.text(d, "deletedBy"))
                .build());
    }
}
