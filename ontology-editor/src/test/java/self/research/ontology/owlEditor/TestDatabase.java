package self.research.ontology.owlEditor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import self.research.ontology.common.db.testing.EmbeddedTestDatabase;

import javax.sql.DataSource;

public final class TestDatabase {

    private TestDatabase() {
    }

    public static DataSource dataSource() {
        return EmbeddedTestDatabase.dataSource();
    }

    public static String jdbcUrl() {
        return EmbeddedTestDatabase.jdbcUrl();
    }

    public static JdbcTemplate jdbc() {
        return EmbeddedTestDatabase.jdbc();
    }

    public static TransactionTemplate transactions() {
        return EmbeddedTestDatabase.transactions();
    }
}
