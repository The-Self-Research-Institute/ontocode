package self.research.ontology.auth.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.auth.model.FileMetadata;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcFileMetadataRepository implements FileMetadataRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("file_metadata")
            .key("id")
            .columns("id", "file_id", "file_name", "project_id", "workspace_id", "file_size", "file_type",
                    "extension", "blob_id", "storage_location", "uploaded_by", "uploader_email", "uploader_username",
                    "uploaded_at", "status", "is_deleted", "deleted_at", "deleted_by")
            .build();

    private static final RowMapper<FileMetadata> ROWS = (rs, i) -> {
        FileMetadata f = new FileMetadata();
        f.setId(rs.getString("id"));
        f.setFileId(rs.getString("file_id"));
        f.setFileName(rs.getString("file_name"));
        f.setProjectId(rs.getString("project_id"));
        f.setWorkspaceId(rs.getString("workspace_id"));
        f.setFileSize(AuthSql.readLong(rs, "file_size"));
        f.setFileType(rs.getString("file_type"));
        f.setExtension(rs.getString("extension"));
        f.setBlobId(rs.getString("blob_id"));
        f.setStorageLocation(rs.getString("storage_location"));
        f.setUploadedBy(rs.getString("uploaded_by"));
        f.setUploaderEmail(rs.getString("uploader_email"));
        f.setUploaderUsername(rs.getString("uploader_username"));
        f.setUploadedAt(Instants.readLocal(rs, "uploaded_at"));
        f.setStatus(rs.getString("status"));
        f.setIsDeleted(AuthSql.readBoolean(rs, "is_deleted"));
        f.setDeletedAt(Instants.readLocal(rs, "deleted_at"));
        f.setDeletedBy(rs.getString("deleted_by"));
        return f;
    };

    private final JdbcTemplate jdbc;

    public JdbcFileMetadataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private List<FileMetadata> query(String where, Object... args) {
        return jdbc.query("select * from file_metadata where " + where + " order by uploaded_at, id", ROWS, args);
    }

    @Override
    public Optional<FileMetadata> findById(String id) {
        return id == null ? Optional.empty() : AuthSql.first(query("id = ?", id));
    }

    @Override
    public FileMetadata save(FileMetadata f) {
        f.setId(AuthSql.idOrNew(f.getId()));
        AuthSql.update(jdbc, UPSERT.sql(), f.getId(), f.getFileId(), f.getFileName(), f.getProjectId(),
                f.getWorkspaceId(), f.getFileSize(), f.getFileType(), f.getExtension(), f.getBlobId(),
                f.getStorageLocation(), f.getUploadedBy(), f.getUploaderEmail(), f.getUploaderUsername(),
                AuthSql.db(f.getUploadedAt()), f.getStatus(), f.getIsDeleted(), AuthSql.db(f.getDeletedAt()),
                f.getDeletedBy());
        return f;
    }

    @Override
    public void deleteById(String id) {
        if (id != null) {
            jdbc.update("delete from file_metadata where id = ?", id);
        }
    }

    @Override
    public void deleteAll(Iterable<? extends FileMetadata> files) {
        for (FileMetadata f : files) {
            deleteById(f.getId());
        }
    }

    @Override
    public List<FileMetadata> findAllByIsDeletedTrueAndDeletedAtBefore(LocalDateTime cutoff) {
        return query("is_deleted is true and deleted_at < ?", AuthSql.db(cutoff));
    }

    @Override
    public Optional<FileMetadata> findByFileId(String fileId) {
        return fileId == null ? Optional.empty() : AuthSql.first(query("file_id = ?", fileId));
    }

    @Override
    public List<FileMetadata> findByProjectId(String projectId) {
        return query("project_id = ?", projectId);
    }

    @Override
    public List<FileMetadata> findByProjectIdAndStatus(String projectId, String status) {
        return query("project_id = ? and status = ?", projectId, status);
    }

    @Override
    public List<FileMetadata> findByWorkspaceId(String workspaceId) {
        return query("workspace_id = ?", workspaceId);
    }

    @Override
    public List<FileMetadata> findByWorkspaceIdAndStatus(String workspaceId, String status) {
        return query("workspace_id = ? and status = ?", workspaceId, status);
    }
}
