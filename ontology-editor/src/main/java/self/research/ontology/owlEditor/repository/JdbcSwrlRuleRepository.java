package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.owlEditor.model.SwrlRuleEntity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcSwrlRuleRepository implements SwrlRuleRepository {

    private static final String UPSERT = "insert into swrl_rule (id, project_id, rule_name, rule_text, comment, category, "
            + "enabled, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "on conflict (id) do update set project_id = excluded.project_id, rule_name = excluded.rule_name, "
            + "rule_text = excluded.rule_text, comment = excluded.comment, category = excluded.category, "
            + "enabled = excluded.enabled, created_at = excluded.created_at, updated_at = excluded.updated_at";

    private static final RowMapper<SwrlRuleEntity> MAPPER = JdbcSwrlRuleRepository::map;

    private final JdbcTemplate jdbc;

    public JdbcSwrlRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SwrlRuleEntity> findByProjectIdOrderByCreatedAtDesc(String projectId) {
        return EditorSql.query(jdbc, "select * from swrl_rule where project_id = ? order by created_at desc nulls last, id desc",
                MAPPER, projectId);
    }

    @Override
    public Optional<SwrlRuleEntity> findById(String id) {
        return EditorSql.first(EditorSql.query(jdbc, "select * from swrl_rule where id = ?", MAPPER, id));
    }

    @Override
    public SwrlRuleEntity save(SwrlRuleEntity e) {
        if (e.getId() == null || e.getId().isBlank()) {
            e.setId(Ids.newObjectId());
        }
        EditorSql.update(jdbc, UPSERT, e.getId(), e.getProjectId(), e.getRuleName(), e.getRuleText(), e.getComment(),
                e.getCategory(), e.isEnabled(), EditorSql.toDb(e.getCreatedAt()), EditorSql.toDb(e.getUpdatedAt()));
        return e;
    }

    @Override
    public void deleteById(String id) {
        EditorSql.update(jdbc, "delete from swrl_rule where id = ?", id);
    }

    private static SwrlRuleEntity map(ResultSet rs, int row) throws SQLException {
        SwrlRuleEntity e = new SwrlRuleEntity();
        e.setId(rs.getString("id"));
        e.setProjectId(rs.getString("project_id"));
        e.setRuleName(rs.getString("rule_name"));
        e.setRuleText(rs.getString("rule_text"));
        e.setComment(rs.getString("comment"));
        e.setCategory(rs.getString("category"));
        e.setEnabled(rs.getBoolean("enabled"));
        e.setCreatedAt(EditorSql.readDate(rs, "created_at"));
        e.setUpdatedAt(EditorSql.readDate(rs, "updated_at"));
        return e;
    }
}
