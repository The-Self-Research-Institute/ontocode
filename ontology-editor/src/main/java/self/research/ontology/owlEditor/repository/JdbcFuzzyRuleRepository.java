package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.model.FuzzyRuleEntity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcFuzzyRuleRepository implements FuzzyRuleRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("fuzzy_rule").key("id")
            .columns("id", "project_id", "name", "rule_condition", "rule_action", "enabled", "created_at", "updated_at")
            .build();

    private static final RowMapper<FuzzyRuleEntity> MAPPER = JdbcFuzzyRuleRepository::map;

    private final JdbcTemplate jdbc;

    public JdbcFuzzyRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FuzzyRuleEntity> findByProjectId(String projectId) {
        return EditorSql.query(jdbc, "select * from fuzzy_rule where project_id = ? order by created_at, id", MAPPER,
                projectId);
    }

    @Override
    public Optional<FuzzyRuleEntity> findById(String id) {
        return EditorSql.first(EditorSql.query(jdbc, "select * from fuzzy_rule where id = ?", MAPPER, id));
    }

    @Override
    public FuzzyRuleEntity save(FuzzyRuleEntity e) {
        if (e.getId() == null || e.getId().isBlank()) {
            e.setId(Ids.newObjectId());
        }
        EditorSql.update(jdbc, UPSERT.sql(), e.getId(), e.getProjectId(), e.getName(), e.getCondition(), e.getAction(),
                e.isEnabled(), EditorSql.toDb(e.getCreatedAt()), EditorSql.toDb(e.getUpdatedAt()));
        return e;
    }

    @Override
    public void deleteById(String id) {
        EditorSql.update(jdbc, "delete from fuzzy_rule where id = ?", id);
    }

    private static FuzzyRuleEntity map(ResultSet rs, int row) throws SQLException {
        FuzzyRuleEntity e = new FuzzyRuleEntity();
        e.setId(rs.getString("id"));
        e.setProjectId(rs.getString("project_id"));
        e.setName(rs.getString("name"));
        e.setCondition(rs.getString("rule_condition"));
        e.setAction(rs.getString("rule_action"));
        e.setEnabled(rs.getBoolean("enabled"));
        e.setCreatedAt(EditorSql.readDate(rs, "created_at"));
        e.setUpdatedAt(EditorSql.readDate(rs, "updated_at"));
        return e;
    }
}
