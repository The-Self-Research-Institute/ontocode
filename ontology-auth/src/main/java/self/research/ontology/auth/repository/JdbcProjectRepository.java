package self.research.ontology.auth.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import self.research.ontology.auth.model.Project;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlLists;
import self.research.ontology.common.db.sql.SqlUpsert;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository("authProjectRepository")
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcProjectRepository implements ProjectRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("project")
            .key("id")
            .columns("id", "project_id", "name", "description", "workspace_id", "owner_id", "status", "tags",
                    "file_ids", "visibility", "created_at", "updated_at", "is_deleted", "deleted_at", "deleted_by")
            .build();

    private static final String NOT_DELETED = "p.is_deleted is not true";

    private static final RowMapper<Project> ROWS = (rs, i) -> {
        Project p = new Project();
        p.setId(rs.getString("id"));
        p.setProjectId(rs.getString("project_id"));
        p.setName(rs.getString("name"));
        p.setDescription(rs.getString("description"));
        p.setWorkspaceId(rs.getString("workspace_id"));
        p.setOwnerId(rs.getString("owner_id"));
        p.setStatus(rs.getString("status"));
        p.setTags(AuthSql.readListOrEmpty(rs, "tags"));
        p.setFileIds(AuthSql.readListOrEmpty(rs, "file_ids"));
        p.setVisibility(rs.getString("visibility"));
        p.setCreatedAt(Instants.readLocal(rs, "created_at"));
        p.setUpdatedAt(Instants.readLocal(rs, "updated_at"));
        p.setIsDeleted(AuthSql.readBoolean(rs, "is_deleted"));
        p.setDeletedAt(Instants.readLocal(rs, "deleted_at"));
        p.setDeletedBy(rs.getString("deleted_by"));
        p.setMembers(new ArrayList<>());
        p.setFiles(new ArrayList<>());
        return p;
    };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcProjectRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private List<Project> query(String where, Object... args) {
        List<Project> rows = jdbc.query("select p.* from project p where " + where + " order by p.created_at, p.id",
                ROWS, args);
        attachChildren(rows);
        return rows;
    }

    private Optional<Project> one(String where, Object... args) {
        List<Project> rows = query(where, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private void attachChildren(List<Project> projects) {
        if (projects.isEmpty()) {
            return;
        }
        Map<String, Project> byRow = new LinkedHashMap<>();
        projects.forEach(p -> byRow.put(p.getId(), p));
        SqlLists.inChunks(new ArrayList<>(byRow.keySet()), 1000, ids -> {
            String marks = SqlLists.placeholders(ids.size());
            jdbc.query("select * from project_member where project_row_id in (" + marks
                            + ") order by project_row_id, position",
                    rs -> {
                        Project.ProjectMember m = new Project.ProjectMember();
                        m.setUserId(rs.getString("user_id"));
                        m.setUsername(rs.getString("username"));
                        m.setEmail(rs.getString("email"));
                        m.setRole(rs.getString("role"));
                        m.setJoinedAt(Instants.readLocal(rs, "joined_at"));
                        m.setWorkspaceEditorLink(rs.getString("workspace_editor_link"));
                        byRow.get(rs.getString("project_row_id")).getMembers().add(m);
                    }, ids.toArray());
            jdbc.query("select * from project_file where project_row_id in (" + marks
                            + ") order by project_row_id, position",
                    rs -> {
                        Project.FileMetadataInfo f = new Project.FileMetadataInfo();
                        f.setFileId(rs.getString("file_id"));
                        f.setFileName(rs.getString("file_name"));
                        f.setFileSize(AuthSql.readLong(rs, "file_size"));
                        f.setFileType(rs.getString("file_type"));
                        f.setExtension(rs.getString("extension"));
                        f.setUploadedBy(rs.getString("uploaded_by"));
                        f.setUploaderUsername(rs.getString("uploader_username"));
                        f.setUploaderEmail(rs.getString("uploader_email"));
                        f.setUploadedAt(Instants.readLocal(rs, "uploaded_at"));
                        f.setStatus(rs.getString("status"));
                        byRow.get(rs.getString("project_row_id")).getFiles().add(f);
                    }, ids.toArray());
        });
    }

    @Override
    public Optional<Project> findById(String id) {
        return id == null ? Optional.empty() : one("p.id = ?", id);
    }

    @Override
    public Project save(Project p) {
        p.setId(AuthSql.idOrNew(p.getId()));
        tx.executeWithoutResult(status -> {
            AuthSql.update(jdbc, UPSERT.sql(), p.getId(), p.getProjectId(), p.getName(), p.getDescription(),
                    p.getWorkspaceId(), p.getOwnerId(), p.getStatus(), AuthSql.textList(p.getTags()),
                    AuthSql.textList(p.getFileIds()), p.getVisibility(), AuthSql.db(p.getCreatedAt()),
                    AuthSql.db(p.getUpdatedAt()), p.getIsDeleted(), AuthSql.db(p.getDeletedAt()), p.getDeletedBy());
            jdbc.update("delete from project_member where project_row_id = ?", p.getId());
            jdbc.update("delete from project_file where project_row_id = ?", p.getId());
            List<Object[]> members = new ArrayList<>();
            int position = 0;
            for (Project.ProjectMember m : p.getMembers()) {
                members.add(new Object[]{p.getId(), position++, m.getUserId(), m.getUsername(), m.getEmail(),
                        m.getRole(), AuthSql.db(m.getJoinedAt()), m.getWorkspaceEditorLink()});
            }
            if (!members.isEmpty()) {
                jdbc.batchUpdate("insert into project_member (project_row_id, position, user_id, username, email, "
                        + "role, joined_at, workspace_editor_link) values (?, ?, ?, ?, ?, ?, ?, ?)", members);
            }
            List<Object[]> files = new ArrayList<>();
            position = 0;
            for (Project.FileMetadataInfo f : p.getFiles()) {
                files.add(new Object[]{p.getId(), position++, f.getFileId(), f.getFileName(), f.getFileSize(),
                        f.getFileType(), f.getExtension(), f.getUploadedBy(), f.getUploaderUsername(),
                        f.getUploaderEmail(), AuthSql.db(f.getUploadedAt()), f.getStatus()});
            }
            if (!files.isEmpty()) {
                jdbc.batchUpdate("insert into project_file (project_row_id, position, file_id, file_name, file_size, "
                        + "file_type, extension, uploaded_by, uploader_username, uploader_email, uploaded_at, status) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", files);
            }
        });
        return p;
    }

    @Override
    public void delete(Project project) {
        if (project != null && project.getId() != null) {
            jdbc.update("delete from project where id = ?", project.getId());
        }
    }

    @Override
    public void deleteAll(Iterable<? extends Project> projects) {
        for (Project p : projects) {
            delete(p);
        }
    }

    @Override
    public List<Project> findAllByIsDeletedTrueAndDeletedAtBefore(LocalDateTime cutoff) {
        return query("p.is_deleted is true and p.deleted_at < ?", AuthSql.db(cutoff));
    }

    @Override
    public Optional<Project> findByProjectId(String projectId) {
        return projectId == null ? Optional.empty() : one("p.project_id = ?", projectId);
    }

    @Override
    public List<Project> findAllByProjectId(String projectId) {
        return projectId == null ? List.of() : query("p.project_id = ?", projectId);
    }

    @Override
    public Optional<Project> findActiveByProjectId(String projectId) {
        return projectId == null ? Optional.empty() : one("p.project_id = ? and " + NOT_DELETED, projectId);
    }

    @Override
    public List<Project> findAllActiveByProjectId(String projectId) {
        return projectId == null ? List.of() : query("p.project_id = ? and " + NOT_DELETED, projectId);
    }

    @Override
    public List<Project> findByWorkspaceId(String workspaceId) {
        return query("p.workspace_id = ?", workspaceId);
    }

    @Override
    public List<Project> findActiveByWorkspaceId(String workspaceId) {
        return query("p.workspace_id = ? and " + NOT_DELETED, workspaceId);
    }

    @Override
    public List<Project> findByWorkspaceIdAndStatus(String workspaceId, String status) {
        return query("p.workspace_id = ? and p.status = ?", workspaceId, status);
    }

    @Override
    public List<Project> findByOwnerId(String ownerId) {
        return query("p.owner_id = ?", ownerId);
    }

    @Override
    public List<Project> findByOwnerIdAndStatus(String ownerId, String status) {
        return query("p.owner_id = ? and p.status = ?", ownerId, status);
    }

    @Override
    public List<Project> findByMembers_UserId(String userId) {
        return query("exists (select 1 from project_member m where m.project_row_id = p.id and m.user_id = ?)",
                userId);
    }

    @Override
    public boolean existsByProjectId(String projectId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from project where project_id = ?)", Boolean.class, projectId));
    }
}
