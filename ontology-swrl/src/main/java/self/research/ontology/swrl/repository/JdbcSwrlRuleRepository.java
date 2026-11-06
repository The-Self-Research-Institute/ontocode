package self.research.ontology.swrl.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.swrl.model.SwrlRule;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcSwrlRuleRepository implements SwrlRuleRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("swrl_rule")
            .key("id")
            .columns("id", "project_id", "rule_name", "rule_text", "comment", "category", "enabled", "created_at",
                    "updated_at", "created_by", "updated_by", "execution_count", "last_execution_time",
                    "last_executed_at", "validated", "validation_error", "migrated_at")
            .build();

    private static final RowMapper<SwrlRule> MAPPER = JdbcSwrlRuleRepository::map;

    private final JdbcTemplate jdbc;

    public JdbcSwrlRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SwrlRule> findByProjectIdAndMigratedAtIsNull(String projectId) {
        return jdbc.query("select * from swrl_rule where project_id = ? and migrated_at is null", MAPPER, projectId);
    }

    @Override
    public Optional<SwrlRule> findByIdAndProjectId(String id, String projectId) {
        List<SwrlRule> rows = jdbc.query("select * from swrl_rule where id = ? and project_id = ?", MAPPER, id, projectId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public SwrlRule save(SwrlRule rule) {
        if (rule.getId() == null) {
            rule.setId(Ids.newObjectId());
        }
        jdbc.update(UPSERT.sql(), rule.getId(), rule.getProjectId(), rule.getRuleName(), rule.getRuleText(),
                rule.getComment(), rule.getCategory(), rule.isEnabled(), Instants.toDb(rule.getCreatedAt()),
                Instants.toDb(rule.getUpdatedAt()), rule.getCreatedBy(), rule.getUpdatedBy(), rule.getExecutionCount(),
                rule.getLastExecutionTime(), Instants.toDb(rule.getLastExecutedAt()), rule.isValidated(),
                rule.getValidationError(), Instants.toDb(rule.getMigratedAt()));
        return rule;
    }

    private static SwrlRule map(ResultSet rs, int row) throws SQLException {
        SwrlRule rule = new SwrlRule();
        rule.setId(rs.getString("id"));
        rule.setProjectId(rs.getString("project_id"));
        rule.setRuleName(rs.getString("rule_name"));
        rule.setRuleText(rs.getString("rule_text"));
        rule.setComment(rs.getString("comment"));
        rule.setCategory(rs.getString("category"));
        rule.setEnabled(rs.getBoolean("enabled"));
        rule.setCreatedAt(Instants.readLocal(rs, "created_at"));
        rule.setUpdatedAt(Instants.readLocal(rs, "updated_at"));
        rule.setCreatedBy(rs.getString("created_by"));
        rule.setUpdatedBy(rs.getString("updated_by"));
        rule.setExecutionCount(rs.getInt("execution_count"));
        long lastTime = rs.getLong("last_execution_time");
        rule.setLastExecutionTime(rs.wasNull() ? null : lastTime);
        rule.setLastExecutedAt(Instants.readLocal(rs, "last_executed_at"));
        rule.setValidated(rs.getBoolean("validated"));
        rule.setValidationError(rs.getString("validation_error"));
        rule.setMigratedAt(Instants.readLocal(rs, "migrated_at"));
        return rule;
    }
}
