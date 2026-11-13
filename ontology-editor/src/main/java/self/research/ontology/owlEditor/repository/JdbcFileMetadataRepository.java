package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcFileMetadataRepository implements FileMetadataRepository {

    private static final RowMapper<FileMetadataRecord> MAPPER = (rs, i) -> new FileMetadataRecord(
            rs.getString("file_id"), rs.getString("file_name"), rs.getString("project_id"),
            rs.getString("workspace_id"), rs.getString("blob_id"), rs.getBoolean("is_deleted"));

    private static final String COLUMNS = "select file_id, file_name, project_id, workspace_id, blob_id, is_deleted "
            + "from file_metadata where file_id = ?";

    private final JdbcTemplate jdbc;

    public JdbcFileMetadataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<FileMetadataRecord> findByFileId(String fileId) {
        return EditorSql.first(jdbc.query(COLUMNS, MAPPER, fileId));
    }

    @Override
    public Optional<FileMetadataRecord> findActiveByFileId(String fileId) {
        return EditorSql.first(jdbc.query(COLUMNS + " and is_deleted is not true", MAPPER, fileId));
    }
}
