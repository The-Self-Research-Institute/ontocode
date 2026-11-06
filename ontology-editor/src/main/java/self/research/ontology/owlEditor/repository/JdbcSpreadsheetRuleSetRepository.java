package self.research.ontology.owlEditor.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.model.SpreadsheetRuleSetEntity;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleSpec;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcSpreadsheetRuleSetRepository implements SpreadsheetRuleSetRepository {

    private static final TypeReference<List<RuleSpec>> RULES = new TypeReference<>() {
    };

    private static final SqlUpsert UPSERT = SqlUpsert.into("spreadsheet_rule_set").key("id")
            .columns("id", "project_id", "name", "description", "rules", "created_by", "created_by_user_id",
                    "updated_by", "created_at", "updated_at")
            .build();

    private static final RowMapper<SpreadsheetRuleSetEntity> MAPPER = JdbcSpreadsheetRuleSetRepository::map;

    private final JdbcTemplate jdbc;

    public JdbcSpreadsheetRuleSetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SpreadsheetRuleSetEntity> findByProjectIdOrderByUpdatedAtDesc(String projectId) {
        return EditorSql.query(jdbc, "select * from spreadsheet_rule_set where project_id = ? "
                + "order by updated_at desc nulls last, id desc", MAPPER, projectId);
    }

    @Override
    public Optional<SpreadsheetRuleSetEntity> findByProjectIdAndName(String projectId, String name) {
        return EditorSql.first(EditorSql.query(jdbc,
                "select * from spreadsheet_rule_set where project_id = ? and name = ?", MAPPER, projectId, name));
    }

    @Override
    public Optional<SpreadsheetRuleSetEntity> findById(String id) {
        return EditorSql.first(EditorSql.query(jdbc, "select * from spreadsheet_rule_set where id = ?", MAPPER, id));
    }

    @Override
    public SpreadsheetRuleSetEntity save(SpreadsheetRuleSetEntity e) {
        if (e.getId() == null || e.getId().isBlank()) {
            e.setId(Ids.newObjectId());
        }
        List<RuleSpec> rules = e.getRules() == null ? new ArrayList<>() : e.getRules();
        EditorSql.update(jdbc, UPSERT.sql(), e.getId(), e.getProjectId(), e.getName(), e.getDescription(),
                EditorSql.json(ruleMaps(rules)), e.getCreatedBy(), e.getCreatedByUserId(), e.getUpdatedBy(),
                EditorSql.toDb(e.getCreatedAt()), EditorSql.toDb(e.getUpdatedAt()));
        return e;
    }

    private static List<Map<String, Object>> ruleMaps(List<RuleSpec> rules) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RuleSpec r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sheetName", r.sheetName());
            m.put("startColumn", r.startColumn());
            m.put("endColumn", r.endColumn());
            m.put("startRow", r.startRow());
            m.put("endRow", r.endRow());
            m.put("rule", r.rule());
            m.put("comment", r.comment());
            m.put("active", r.active());
            out.add(m);
        }
        return out;
    }

    @Override
    public void delete(SpreadsheetRuleSetEntity entity) {
        if (entity != null && entity.getId() != null) {
            EditorSql.update(jdbc, "delete from spreadsheet_rule_set where id = ?", entity.getId());
        }
    }

    private static SpreadsheetRuleSetEntity map(ResultSet rs, int row) throws SQLException {
        SpreadsheetRuleSetEntity e = new SpreadsheetRuleSetEntity();
        e.setId(rs.getString("id"));
        e.setProjectId(rs.getString("project_id"));
        e.setName(rs.getString("name"));
        e.setDescription(rs.getString("description"));
        List<RuleSpec> rules = EditorSql.readJson(rs, "rules", RULES);
        e.setRules(rules == null ? new ArrayList<>() : rules);
        e.setCreatedBy(rs.getString("created_by"));
        e.setCreatedByUserId(rs.getString("created_by_user_id"));
        e.setUpdatedBy(rs.getString("updated_by"));
        e.setCreatedAt(EditorSql.readDate(rs, "created_at"));
        e.setUpdatedAt(EditorSql.readDate(rs, "updated_at"));
        return e;
    }
}
