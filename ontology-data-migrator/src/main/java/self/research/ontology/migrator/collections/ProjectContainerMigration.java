package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.UniqueRule;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.ArrayList;
import java.util.List;

public final class ProjectContainerMigration implements CollectionMigration {

    @Override
    public String name() {
        return "projects:containers";
    }

    @Override
    public String sourceCollection() {
        return "projects";
    }

    @Override
    public boolean accepts(Document d) {
        return ProjectKinds.isContainer(d);
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.field("project_id", "projectId"));
    }

    @Override
    public List<Write> map(Document d) {
        String id = Values.idText(d.get("_id"));
        List<Write> writes = new ArrayList<>();
        writes.add(Write.upsert("project", "id")
                .put("id", id)
                .put("project_id", Values.required(d, "projectId"))
                .put("name", Values.text(d, "name"))
                .put("description", Values.text(d, "description"))
                .put("workspace_id", Values.text(d, "workspaceId"))
                .put("owner_id", Values.text(d, "ownerId"))
                .put("status", Values.text(d, "status"))
                .put("tags", Values.listOrEmpty(d, "tags"))
                .put("file_ids", Values.listOrEmpty(d, "fileIds"))
                .put("visibility", Values.text(d, "visibility"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("updated_at", Values.time(d, "updatedAt"))
                .put("is_deleted", Values.bool(d, "isDeleted", false))
                .put("deleted_at", Values.time(d, "deletedAt"))
                .put("deleted_by", Values.text(d, "deletedBy"))
                .build());
        writes.add(Write.deleteChildren("project_member", "project_row_id", id));
        writes.add(Write.deleteChildren("project_file", "project_row_id", id));
        int position = 0;
        for (Document m : Values.documents(d, "members")) {
            writes.add(Write.upsert("project_member", "project_row_id", "position")
                    .put("project_row_id", id)
                    .put("position", position++)
                    .put("user_id", Values.text(m, "userId"))
                    .put("username", Values.text(m, "username"))
                    .put("email", Values.text(m, "email"))
                    .put("role", Values.text(m, "role"))
                    .put("joined_at", Values.time(m, "joinedAt"))
                    .put("workspace_editor_link", Values.text(m, "workspaceEditorLink"))
                    .build());
        }
        position = 0;
        for (Document f : Values.documents(d, "files")) {
            writes.add(Write.upsert("project_file", "project_row_id", "position")
                    .put("project_row_id", id)
                    .put("position", position++)
                    .put("file_id", Values.text(f, "fileId"))
                    .put("file_name", Values.text(f, "fileName"))
                    .put("file_size", Values.longValue(f, "fileSize"))
                    .put("file_type", Values.text(f, "fileType"))
                    .put("extension", Values.text(f, "extension"))
                    .put("uploaded_by", Values.text(f, "uploadedBy"))
                    .put("uploader_username", Values.text(f, "uploaderUsername"))
                    .put("uploader_email", Values.text(f, "uploaderEmail"))
                    .put("uploaded_at", Values.time(f, "uploadedAt"))
                    .put("status", Values.text(f, "status"))
                    .build());
        }
        return writes;
    }
}
