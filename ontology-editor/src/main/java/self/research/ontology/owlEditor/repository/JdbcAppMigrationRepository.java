package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcAppMigrationRepository implements AppMigrationRepository {

    private final JdbcTemplate jdbc;

    public JdbcAppMigrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isDone(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from app_migration where id = ?)", Boolean.class, id));
    }

    @Override
    public void markDone(String id, Map<String, Object> details) {
        EditorSql.update(jdbc, "insert into app_migration (id, completed_at, details) values (?, now(), ?) "
                + "on conflict (id) do update set completed_at = now(), details = excluded.details", id, details);
    }
}
