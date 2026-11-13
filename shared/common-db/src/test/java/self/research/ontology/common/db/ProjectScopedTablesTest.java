package self.research.ontology.common.db;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.testing.EmbeddedTestDatabase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScopedTablesTest {

    private final JdbcTemplate jdbc = EmbeddedTestDatabase.jdbc();

    @Test
    void purgeRemovesOnlyThatProjectsRowsFromEveryRegisteredTableAndSkipsTablesThatDoNotExistYet() {
        jdbc.execute("create table if not exists scoped_a (id serial primary key, project_id text)");
        jdbc.execute("create table if not exists scoped_b (id serial primary key, project_id text)");
        jdbc.update("delete from scoped_a");
        jdbc.update("delete from scoped_b");
        jdbc.update("insert into scoped_a(project_id) values ('p1'), ('p1'), ('p2')");
        jdbc.update("insert into scoped_b(project_id) values ('p1'), ('p2')");
        ProjectScopedTables.register("scoped_a", "scoped_b", "scoped_not_there_yet");

        int removed = ProjectScopedTables.purge(jdbc, "p1");

        assertEquals(3, removed);
        assertEquals(1, jdbc.queryForObject("select count(*) from scoped_a", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from scoped_b", Integer.class));
        assertTrue(ProjectScopedTables.names().contains("scoped_not_there_yet"));
    }

    @Test
    void registeringAnUnsafeNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProjectScopedTables.register("x; drop table y"));
    }
}
