package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlLists;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.document.ProjectDocument;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcProjectRepository implements ProjectRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("ontology_file")
            .key("id")
            .columns("id", "name", "file_name", "owner_email", "owner_id", "project_id", "workspace_id", "blob_id",
                    "status", "status_message", "created_at", "updated_at", "metadata", "mutation_version",
                    "main_graph_revision")
            .build();

    private static final RowMapper<ProjectDocument> MAPPER = (rs, i) -> {
        ProjectDocument doc = new ProjectDocument();
        doc.setId(rs.getString("id"));
        doc.setName(rs.getString("name"));
        doc.setFilename(rs.getString("file_name"));
        doc.setOwnerEmail(rs.getString("owner_email"));
        doc.setOwnerId(rs.getString("owner_id"));
        doc.setProjectId(rs.getString("project_id"));
        doc.setWorkspaceId(rs.getString("workspace_id"));
        doc.setGridfsFileId(rs.getString("blob_id"));
        doc.setStatus(rs.getString("status"));
        doc.setStatusMessage(rs.getString("status_message"));
        doc.setCreatedAt(Instants.read(rs, "created_at"));
        doc.setUpdatedAt(Instants.read(rs, "updated_at"));
        doc.setMetadata(EditorSql.readMap(rs, "metadata"));
        doc.setMutationVersion(EditorSql.readLong(rs, "mutation_version"));
        doc.setMainGraphRevision(EditorSql.readLong(rs, "main_graph_revision"));
        return doc;
    };

    private final JdbcTemplate jdbc;

    public JdbcProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProjectDocument> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return EditorSql.first(jdbc.query("select * from ontology_file where id = ?", MAPPER, id));
    }

    @Override
    public boolean existsById(String id) {
        return id != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from ontology_file where id = ?)", Boolean.class, id));
    }

    @Override
    public ProjectDocument save(ProjectDocument d) {
        if (d.getId() == null || d.getId().isBlank()) {
            d.setId(Ids.newObjectId());
        }
        EditorSql.update(jdbc, UPSERT.sql(), d.getId(), d.getName(), d.getFilename(), d.getOwnerEmail(), d.getOwnerId(),
                d.getProjectId(), d.getWorkspaceId(), d.getGridfsFileId(), d.getStatus(), d.getStatusMessage(),
                Instants.toDb(d.getCreatedAt()), Instants.toDb(d.getUpdatedAt()), EditorSql.json(d.getMetadata()),
                d.getMutationVersion(), d.getMainGraphRevision());
        return d;
    }

    @Override
    public void deleteById(String id) {
        jdbc.update("delete from ontology_file where id = ?", id);
    }

    @Override
    public List<ProjectDocument> findAll() {
        return jdbc.query("select * from ontology_file", MAPPER);
    }

    @Override
    public List<ProjectDocument> findAllByOrderByUpdatedAtDesc() {
        return jdbc.query("select * from ontology_file order by updated_at desc nulls last, id", MAPPER);
    }

    @Override
    public Optional<ProjectDocument> findFirstByFilenameAndOwnerEmailOrderByUpdatedAtDesc(String filename, String ownerEmail) {
        return EditorSql.first(jdbc.query("select * from ontology_file where file_name = ? and owner_email = ? "
                + "order by updated_at desc nulls last, id limit 1", MAPPER, filename, ownerEmail));
    }

    @Override
    public List<ProjectDocument> findByFilenameAndOwnerEmail(String filename, String ownerEmail) {
        return jdbc.query("select * from ontology_file where file_name = ? and owner_email = ?", MAPPER, filename, ownerEmail);
    }

    @Override
    public Optional<ProjectDocument> findFirstByFilenameOrderByUpdatedAtDesc(String filename) {
        return EditorSql.first(jdbc.query("select * from ontology_file where file_name = ? "
                + "order by updated_at desc nulls last, id limit 1", MAPPER, filename));
    }

    @Override
    public List<ProjectDocument> findByStatusIn(Collection<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select * from ontology_file where status in (" + SqlLists.placeholders(statuses.size()) + ")",
                MAPPER, statuses.toArray());
    }

    @Override
    public List<String> findIdsByImportedIri(String iri) {
        return jdbc.queryForList("select id from ontology_file where (metadata -> 'imports') @> to_jsonb(?::text)",
                String.class, iri);
    }

    @Override
    public void upsertStatus(String id, String status, String statusMessage, String filename, Instant now) {
        EditorSql.update(jdbc, "insert into ontology_file (id, name, created_at, status, status_message, file_name, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?) on conflict (id) do update set status = excluded.status, "
                        + "status_message = excluded.status_message, file_name = excluded.file_name, "
                        + "updated_at = excluded.updated_at",
                id, id, Instants.toDb(now), status, statusMessage, filename, Instants.toDb(now));
    }

    @Override
    public void upsertMetadata(String id, Map<String, Object> metadata, String statusOnInsert, Instant now) {
        EditorSql.update(jdbc, "insert into ontology_file (id, name, created_at, status, metadata, updated_at) "
                        + "values (?, ?, ?, ?, ?::jsonb - 'requireDraftForMembers', ?) on conflict (id) do update set "
                        + "metadata = case when jsonb_exists(ontology_file.metadata, 'requireDraftForMembers') then "
                        + "excluded.metadata || jsonb_build_object('requireDraftForMembers', "
                        + "ontology_file.metadata -> 'requireDraftForMembers') else excluded.metadata end, "
                        + "updated_at = excluded.updated_at",
                id, id, Instants.toDb(now), statusOnInsert, EditorSql.json(metadata), Instants.toDb(now));
    }

    @Override
    public void setRequireDraftForMembers(String projectId, String parentProjectId, boolean value, Instant now) {
        int updated = EditorSql.update(jdbc, "update ontology_file set metadata = jsonb_set("
                        + "coalesce(metadata, '{}'::jsonb), '{requireDraftForMembers}', to_jsonb(?::boolean), true) "
                        + "where project_id = ? or id = ? or starts_with(id, ? || '--')",
                value, parentProjectId, parentProjectId, parentProjectId);
        if (updated == 0) {
            EditorSql.update(jdbc, "insert into ontology_file (id, name, created_at, status, metadata, updated_at) "
                            + "values (?, ?, ?, 'UPLOADED', jsonb_build_object('requireDraftForMembers', ?::boolean), ?) "
                            + "on conflict (id) do update set metadata = jsonb_set(coalesce(ontology_file.metadata, "
                            + "'{}'::jsonb), '{requireDraftForMembers}', to_jsonb(?::boolean), true)",
                    projectId, projectId, Instants.toDb(now), value, Instants.toDb(now), value);
        }
    }

    @Override
    public boolean isRequireDraftForMembers(String parentProjectId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from ontology_file where "
                + "(project_id = ? or id = ? or starts_with(id, ? || '--')) "
                + "and metadata @> '{\"requireDraftForMembers\": true}'::jsonb)", Boolean.class,
                parentProjectId, parentProjectId, parentProjectId));
    }

    @Override
    public void upsertImportProgress(String id, Map<String, Object> progress, String statusMessage, Instant now) {
        EditorSql.update(jdbc, "insert into ontology_file (id, name, created_at, status_message, metadata, updated_at) "
                        + "values (?, ?, ?, ?, jsonb_build_object('importProgress', ?::jsonb), ?) "
                        + "on conflict (id) do update set status_message = excluded.status_message, "
                        + "metadata = jsonb_set(coalesce(ontology_file.metadata, '{}'::jsonb), '{importProgress}', "
                        + "excluded.metadata -> 'importProgress'), updated_at = excluded.updated_at",
                id, id, Instants.toDb(now), statusMessage, EditorSql.json(progress), Instants.toDb(now));
    }

    @Override
    public void upsertOwnerEmail(String id, String ownerEmail, Instant now) {
        EditorSql.update(jdbc, "insert into ontology_file (id, name, created_at, status, owner_email, updated_at) "
                        + "values (?, ?, ?, 'UPLOADED', ?, ?) on conflict (id) do update set "
                        + "owner_email = excluded.owner_email, updated_at = excluded.updated_at",
                id, id, Instants.toDb(now), ownerEmail, Instants.toDb(now));
    }

    @Override
    public void upsertBlobId(String id, String blobId, Instant now) {
        EditorSql.update(jdbc, "insert into ontology_file (id, name, created_at, status, blob_id, updated_at) "
                        + "values (?, ?, ?, 'UPLOADED', ?, ?) on conflict (id) do update set "
                        + "blob_id = excluded.blob_id, updated_at = excluded.updated_at",
                id, id, Instants.toDb(now), blobId, Instants.toDb(now));
    }

    @Override
    public void upsertProjectMetadata(String id, String status, String statusMessage, String filename, String blobId,
                                      String ownerEmail, String ownerId, String workspaceId, String parentProjectId,
                                      Instant now) {
        EditorSql.update(jdbc, "insert into ontology_file (id, name, created_at, status, status_message, file_name, "
                        + "blob_id, updated_at, owner_email, owner_id, workspace_id, project_id) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (id) do update set "
                        + "status = excluded.status, status_message = excluded.status_message, "
                        + "file_name = excluded.file_name, blob_id = excluded.blob_id, "
                        + "updated_at = excluded.updated_at, "
                        + "workspace_id = coalesce(excluded.workspace_id, ontology_file.workspace_id), "
                        + "project_id = coalesce(excluded.project_id, ontology_file.project_id)",
                id, id, Instants.toDb(now), status, statusMessage, filename, blobId, Instants.toDb(now),
                emptyToNull(ownerEmail), blankToNull(ownerId), emptyToNull(workspaceId), emptyToNull(parentProjectId));
    }

    @Override
    public void updateStatus(String id, String status, String statusMessage, Instant now) {
        jdbc.update("update ontology_file set status = ?, status_message = ?, updated_at = ? where id = ?",
                status, statusMessage, Instants.toDb(now), id);
    }

    @Override
    public long incrementMutationVersion(String id, Instant now) {
        List<Long> rows = jdbc.queryForList("update ontology_file set mutation_version = coalesce(mutation_version, 0) + 1, "
                + "updated_at = ? where id = ? returning mutation_version", Long.class, Instants.toDb(now), id);
        return rows.isEmpty() || rows.get(0) == null ? 0L : rows.get(0);
    }

    @Override
    public long incrementMainGraphRevision(String id, Instant now) {
        List<Long> rows = jdbc.queryForList("update ontology_file set main_graph_revision = coalesce(main_graph_revision, 0) + 1, "
                + "updated_at = ? where id = ? returning main_graph_revision", Long.class, Instants.toDb(now), id);
        return rows.isEmpty() || rows.get(0) == null ? 0L : rows.get(0);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
