package self.research.ontology.owlEditor.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.SqlLists;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.document.EntityUsageDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcEntityUsageRepository implements EntityUsageRepository {

    private static final int BATCH_SIZE = 1000;

    private static final TypeReference<List<Map<String, String>>> USAGES = new TypeReference<>() {
    };

    private static final SqlUpsert UPSERT = SqlUpsert.into("entity_usage")
            .key("project_id", "entity_iri")
            .columns("project_id", "entity_iri", "revision", "built_at", "usages")
            .build();

    private static final RowMapper<EntityUsageDocument> MAPPER = (rs, i) -> new EntityUsageDocument(
            rs.getString("project_id"), rs.getString("entity_iri"), rs.getLong("revision"), rs.getLong("built_at"),
            EditorSql.readJson(rs, "usages", USAGES));

    private final JdbcTemplate jdbc;

    public JdbcEntityUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<EntityUsageDocument> findByProjectIdAndEntityIri(String projectId, String entityIri) {
        return EditorSql.first(EditorSql.query(jdbc,
                "select * from entity_usage where project_id = ? and entity_iri = ?", MAPPER, projectId, entityIri));
    }

    @Override
    public void saveAll(List<EntityUsageDocument> documents) {
        SqlLists.inChunks(documents, BATCH_SIZE, chunk -> {
            List<Object[]> rows = new ArrayList<>(chunk.size());
            for (EntityUsageDocument document : chunk) {
                rows.add(new Object[] {document.getProjectId(), document.getEntityIri(), document.getRevision(),
                        document.getBuiltAt(), EditorSql.json(document.getUsages())});
            }
            EditorSql.batch(jdbc, UPSERT.sql(), rows);
        });
    }

    @Override
    public void deleteByProjectIdAndEntityIriIn(String projectId, List<String> entityIris) {
        if (entityIris == null || entityIris.isEmpty()) {
            return;
        }
        EditorSql.update(jdbc, "delete from entity_usage where project_id = ? and entity_iri = any(?)",
                projectId, entityIris);
    }

    @Override
    public void deleteByProjectId(String projectId) {
        jdbc.update("delete from entity_usage where project_id = ?", projectId);
    }
}
