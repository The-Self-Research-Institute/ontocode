package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.SqlLists;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.document.ClassDetailDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcClassDetailRepository implements ClassDetailRepository {

    private static final int BATCH_SIZE = 1000;

    private static final SqlUpsert UPSERT = SqlUpsert.into("class_detail")
            .key("project_id", "class_iri")
            .columns("project_id", "class_iri", "details", "partial", "built_at")
            .build();

    private static final String INSERT_IF_ABSENT = "insert into class_detail "
            + "(project_id, class_iri, details, partial, built_at) values (?, ?, ?, ?, ?) "
            + "on conflict (project_id, class_iri) do nothing";

    private static final RowMapper<ClassDetailDocument> MAPPER = (rs, i) -> new ClassDetailDocument(
            rs.getString("project_id"), rs.getString("class_iri"), EditorSql.readMap(rs, "details"),
            rs.getBoolean("partial"), rs.getLong("built_at"));

    private final JdbcTemplate jdbc;

    public JdbcClassDetailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ClassDetailDocument> findByProjectIdAndClassIri(String projectId, String classIri) {
        return EditorSql.first(EditorSql.query(jdbc,
                "select * from class_detail where project_id = ? and class_iri = ?", MAPPER, projectId, classIri));
    }

    @Override
    public void save(ClassDetailDocument document) {
        EditorSql.update(jdbc, UPSERT.sql(), row(document));
    }

    @Override
    public boolean saveIfAbsent(ClassDetailDocument document) {
        return EditorSql.update(jdbc, INSERT_IF_ABSENT, row(document)) > 0;
    }

    @Override
    public int saveAllIfAbsent(List<ClassDetailDocument> documents) {
        AtomicInteger inserted = new AtomicInteger();
        SqlLists.inChunks(documents, BATCH_SIZE, chunk -> {
            List<Object[]> rows = new ArrayList<>(chunk.size());
            for (ClassDetailDocument document : chunk) {
                rows.add(row(document));
            }
            inserted.addAndGet(EditorSql.batch(jdbc, INSERT_IF_ABSENT, rows));
        });
        return inserted.get();
    }

    @Override
    public void deleteByProjectIdAndClassIriIn(String projectId, List<String> classIris) {
        if (classIris == null || classIris.isEmpty()) {
            return;
        }
        EditorSql.update(jdbc, "delete from class_detail where project_id = ? and class_iri = any(?)",
                projectId, classIris);
    }

    @Override
    public void deleteByProjectId(String projectId) {
        jdbc.update("delete from class_detail where project_id = ?", projectId);
    }

    private static Object[] row(ClassDetailDocument document) {
        return new Object[] {document.getProjectId(), document.getClassIri(), EditorSql.json(document.getDetails()),
                document.isPartial(), document.getBuiltAt()};
    }
}
