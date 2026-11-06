package self.research.ontology.common.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.testing.EmbeddedTestDatabase;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseBlobStoreTest extends BlobStoreContract {

    private final JdbcTemplate jdbc = EmbeddedTestDatabase.jdbc();
    private final StoredFiles files = new StoredFiles(jdbc, new ObjectMapper());
    private final JdbcBlobStore store = new JdbcBlobStore(jdbc, EmbeddedTestDatabase.transactions(), files);

    @BeforeEach
    void clean() {
        jdbc.update("delete from stored_file");
    }

    @Override
    BlobStore store() {
        return store;
    }

    @Override
    JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    String expectedStorage() {
        return "database";
    }

    @Override
    void assertNoPartialContent() {
        assertEquals(0, jdbc.queryForObject("select count(*) from file_chunk", Integer.class));
    }
}
