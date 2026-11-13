package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.model.SpreadsheetImportRecord;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcSpreadsheetImportRecordRepository implements SpreadsheetImportRecordRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("spreadsheet_import_record").key("id")
            .columns("id", "project_id", "user_id", "username", "file_name", "draft", "draft_session_id", "created_at",
                    "axiom_count", "already_present_count", "added_triples", "rolled_back", "rolled_back_at",
                    "rolled_back_by")
            .build();

    private static final RowMapper<SpreadsheetImportRecord> MAPPER = JdbcSpreadsheetImportRecordRepository::map;

    private final JdbcTemplate jdbc;

    public JdbcSpreadsheetImportRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SpreadsheetImportRecord> findByProjectIdAndUserIdAndDraftTrue(String projectId, String userId) {
        return EditorSql.query(jdbc, "select * from spreadsheet_import_record where project_id = ? and user_id = ? "
                + "and draft order by created_at, id", MAPPER, projectId, userId);
    }

    @Override
    public Optional<SpreadsheetImportRecord> findById(String id) {
        return EditorSql.first(EditorSql.query(jdbc, "select * from spreadsheet_import_record where id = ?", MAPPER, id));
    }

    @Override
    public SpreadsheetImportRecord save(SpreadsheetImportRecord r) {
        if (r.getId() == null || r.getId().isBlank()) {
            r.setId(Ids.newObjectId());
        }
        List<String> triples = r.getAddedTriples() == null ? new ArrayList<>() : r.getAddedTriples();
        EditorSql.update(jdbc, UPSERT.sql(), r.getId(), r.getProjectId(), r.getUserId(), r.getUsername(),
                r.getFileName(), r.isDraft(), r.getDraftSessionId(), Instants.toDb(r.getCreatedAt()),
                r.getAxiomCount(), r.getAlreadyPresentCount(), triples, r.isRolledBack(),
                Instants.toDb(r.getRolledBackAt()), r.getRolledBackBy());
        return r;
    }

    @Override
    public void deleteById(String id) {
        EditorSql.update(jdbc, "delete from spreadsheet_import_record where id = ?", id);
    }

    private static SpreadsheetImportRecord map(ResultSet rs, int row) throws SQLException {
        SpreadsheetImportRecord r = new SpreadsheetImportRecord();
        r.setId(rs.getString("id"));
        r.setProjectId(rs.getString("project_id"));
        r.setUserId(rs.getString("user_id"));
        r.setUsername(rs.getString("username"));
        r.setFileName(rs.getString("file_name"));
        r.setDraft(rs.getBoolean("draft"));
        r.setDraftSessionId(rs.getString("draft_session_id"));
        r.setCreatedAt(Instants.read(rs, "created_at"));
        r.setAxiomCount(rs.getInt("axiom_count"));
        r.setAlreadyPresentCount(rs.getInt("already_present_count"));
        List<String> triples = EditorSql.readList(rs, "added_triples");
        r.setAddedTriples(triples == null ? new ArrayList<>() : triples);
        r.setRolledBack(rs.getBoolean("rolled_back"));
        r.setRolledBackAt(Instants.read(rs, "rolled_back_at"));
        r.setRolledBackBy(rs.getString("rolled_back_by"));
        return r;
    }
}
