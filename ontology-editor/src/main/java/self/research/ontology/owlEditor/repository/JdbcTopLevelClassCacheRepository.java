package self.research.ontology.owlEditor.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.document.TopLevelClassCacheDoc;
import self.research.ontology.owlEditor.dto.OntologyDto;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcTopLevelClassCacheRepository implements TopLevelClassCacheRepository {

    private static final TypeReference<List<OntologyDto.TreeNode>> NODES = new TypeReference<>() {
    };

    private static final SqlUpsert UPSERT = SqlUpsert.into("top_level_class_cache")
            .key("project_id")
            .columns("project_id", "nodes", "computed_with_limit", "computed_at", "expires_at")
            .build();

    private static final RowMapper<TopLevelClassCacheDoc> MAPPER = (rs, i) -> {
        TopLevelClassCacheDoc doc = new TopLevelClassCacheDoc();
        doc.setProjectId(rs.getString("project_id"));
        doc.setNodes(EditorSql.readJson(rs, "nodes", NODES));
        doc.setComputedWithLimit(rs.getInt("computed_with_limit"));
        doc.setComputedAt(rs.getLong("computed_at"));
        doc.setExpiresAt(Instants.read(rs, "expires_at") == null ? null : Date.from(Instants.read(rs, "expires_at")));
        return doc;
    };

    private final JdbcTemplate jdbc;

    public JdbcTopLevelClassCacheRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TopLevelClassCacheDoc> findById(String projectId) {
        return EditorSql.first(EditorSql.query(jdbc,
                "select * from top_level_class_cache where project_id = ? "
                        + "and (expires_at is null or expires_at > now())", MAPPER, projectId));
    }

    @Override
    public void save(TopLevelClassCacheDoc doc) {
        EditorSql.update(jdbc, UPSERT.sql(), doc.getProjectId(), EditorSql.json(doc.getNodes()),
                doc.getComputedWithLimit(), doc.getComputedAt(),
                doc.getExpiresAt() == null ? null : Instants.toDb(doc.getExpiresAt().toInstant()));
    }

    @Override
    public void deleteById(String projectId) {
        jdbc.update("delete from top_level_class_cache where project_id = ?", projectId);
    }

    @Override
    public long count() {
        Long total = jdbc.queryForObject("select count(*) from top_level_class_cache "
                + "where expires_at is null or expires_at > now()", Long.class);
        return total == null ? 0 : total;
    }

    @Override
    public void deleteAll() {
        jdbc.update("delete from top_level_class_cache");
    }
}
