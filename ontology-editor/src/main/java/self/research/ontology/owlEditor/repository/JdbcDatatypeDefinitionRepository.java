package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.model.DatatypeDefinitionEntity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcDatatypeDefinitionRepository implements DatatypeDefinitionRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("datatype_definition").key("id")
            .columns("id", "project_id", "datatype_iri", "expression", "definition_type", "created_at", "updated_at",
                    "draft", "owner_user_id", "replaces_id", "deleted")
            .build();

    private static final RowMapper<DatatypeDefinitionEntity> MAPPER = JdbcDatatypeDefinitionRepository::map;

    private final JdbcTemplate jdbc;

    public JdbcDatatypeDefinitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DatatypeDefinitionEntity> findByProjectIdAndDatatypeIriAndDraftNotOrderByCreatedAtDesc(
            String projectId, String datatypeIri, boolean draft) {
        return EditorSql.query(jdbc, "select * from datatype_definition where project_id = ? and datatype_iri = ? "
                + "and draft <> ? order by created_at desc nulls last, id desc", MAPPER, projectId, datatypeIri, draft);
    }

    @Override
    public List<DatatypeDefinitionEntity> findByProjectIdAndDatatypeIriAndOwnerUserIdAndDraftTrue(
            String projectId, String datatypeIri, String ownerUserId) {
        return EditorSql.query(jdbc, "select * from datatype_definition where project_id = ? and datatype_iri = ? "
                + "and owner_user_id = ? and draft order by created_at, id", MAPPER, projectId, datatypeIri, ownerUserId);
    }

    @Override
    public List<DatatypeDefinitionEntity> findByProjectIdAndOwnerUserIdAndDraftTrue(String projectId, String ownerUserId) {
        return EditorSql.query(jdbc, "select * from datatype_definition where project_id = ? and owner_user_id = ? "
                + "and draft order by created_at, id", MAPPER, projectId, ownerUserId);
    }

    @Override
    public Optional<DatatypeDefinitionEntity> findByIdAndProjectId(String id, String projectId) {
        return EditorSql.first(EditorSql.query(jdbc,
                "select * from datatype_definition where id = ? and project_id = ?", MAPPER, id, projectId));
    }

    @Override
    public Optional<DatatypeDefinitionEntity> findById(String id) {
        return EditorSql.first(EditorSql.query(jdbc, "select * from datatype_definition where id = ?", MAPPER, id));
    }

    @Override
    public DatatypeDefinitionEntity save(DatatypeDefinitionEntity e) {
        if (e.getId() == null || e.getId().isBlank()) {
            e.setId(Ids.newObjectId());
        }
        EditorSql.update(jdbc, UPSERT.sql(), e.getId(), e.getProjectId(), e.getDatatypeIri(), e.getExpression(),
                e.getDefinitionType(), EditorSql.toDb(e.getCreatedAt()), EditorSql.toDb(e.getUpdatedAt()), e.isDraft(),
                e.getOwnerUserId(), e.getReplacesId(), e.isDeleted());
        return e;
    }

    @Override
    public void delete(DatatypeDefinitionEntity entity) {
        if (entity != null && entity.getId() != null) {
            deleteById(entity.getId());
        }
    }

    @Override
    public void deleteById(String id) {
        EditorSql.update(jdbc, "delete from datatype_definition where id = ?", id);
    }

    @Override
    public void deleteAll(Iterable<? extends DatatypeDefinitionEntity> entities) {
        for (DatatypeDefinitionEntity entity : entities) {
            delete(entity);
        }
    }

    private static DatatypeDefinitionEntity map(ResultSet rs, int row) throws SQLException {
        DatatypeDefinitionEntity e = new DatatypeDefinitionEntity();
        e.setId(rs.getString("id"));
        e.setProjectId(rs.getString("project_id"));
        e.setDatatypeIri(rs.getString("datatype_iri"));
        e.setExpression(rs.getString("expression"));
        e.setDefinitionType(rs.getString("definition_type"));
        e.setCreatedAt(EditorSql.readDate(rs, "created_at"));
        e.setUpdatedAt(EditorSql.readDate(rs, "updated_at"));
        e.setDraft(rs.getBoolean("draft"));
        e.setOwnerUserId(rs.getString("owner_user_id"));
        e.setReplacesId(rs.getString("replaces_id"));
        e.setDeleted(rs.getBoolean("deleted"));
        return e;
    }
}
