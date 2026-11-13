package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.model.SparqlQueryEntity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcSparqlQueryRepository implements SparqlQueryRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("sparql_query").key("id")
            .columns("id", "project_id", "name", "query_text", "created_at", "updated_at")
            .build();

    private static final RowMapper<SparqlQueryEntity> MAPPER = JdbcSparqlQueryRepository::map;

    private final JdbcTemplate jdbc;

    public JdbcSparqlQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SparqlQueryEntity> findByProjectId(String projectId) {
        return EditorSql.query(jdbc, "select * from sparql_query where project_id = ? order by created_at, id", MAPPER,
                projectId);
    }

    @Override
    public Optional<SparqlQueryEntity> findById(String id) {
        return EditorSql.first(EditorSql.query(jdbc, "select * from sparql_query where id = ?", MAPPER, id));
    }

    @Override
    public SparqlQueryEntity save(SparqlQueryEntity e) {
        if (e.getId() == null || e.getId().isBlank()) {
            e.setId(Ids.newObjectId());
        }
        EditorSql.update(jdbc, UPSERT.sql(), e.getId(), e.getProjectId(), e.getName(), e.getQueryText(),
                EditorSql.toDb(e.getCreatedAt()), EditorSql.toDb(e.getUpdatedAt()));
        return e;
    }

    @Override
    public void deleteById(String id) {
        EditorSql.update(jdbc, "delete from sparql_query where id = ?", id);
    }

    private static SparqlQueryEntity map(ResultSet rs, int row) throws SQLException {
        SparqlQueryEntity e = new SparqlQueryEntity();
        e.setId(rs.getString("id"));
        e.setProjectId(rs.getString("project_id"));
        e.setName(rs.getString("name"));
        e.setQueryText(rs.getString("query_text"));
        e.setCreatedAt(EditorSql.readDate(rs, "created_at"));
        e.setUpdatedAt(EditorSql.readDate(rs, "updated_at"));
        return e;
    }
}
