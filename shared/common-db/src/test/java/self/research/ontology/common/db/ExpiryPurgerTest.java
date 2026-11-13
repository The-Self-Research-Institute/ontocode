package self.research.ontology.common.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(classes = {TestApplication.class, ExpiryPurgerTest.Config.class},
        properties = "ontocode.db.expiry.initial-delay-ms=3600000")
class ExpiryPurgerTest {

    @TestConfiguration
    static class Config {
        @Bean
        ExpiringTable cacheTable() {
            return ExpiringTable.of("expiry_test_cache");
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Autowired
    ExpiryPurger purger;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void purgesOnlyExpiredRowsInBatchesAndKeepsRowsWithoutExpiry() {
        jdbc.execute("create table if not exists expiry_test_cache (id int primary key, expires_at timestamptz)");
        jdbc.execute("delete from expiry_test_cache");
        jdbc.update("insert into expiry_test_cache select g, now() - interval '1 minute' from generate_series(1, 12000) g");
        jdbc.update("insert into expiry_test_cache select g, now() + interval '1 hour' from generate_series(20001, 20010) g");
        jdbc.update("insert into expiry_test_cache values (30001, null)");

        int purged = purger.purge(ExpiringTable.of("expiry_test_cache"));

        assertEquals(12000, purged);
        assertEquals(11, jdbc.queryForObject("select count(*) from expiry_test_cache", Integer.class));
        assertEquals(0, purger.purge(ExpiringTable.of("expiry_test_cache")));
    }

    @Test
    void scheduledRunPurgesRegisteredTables() {
        jdbc.execute("create table if not exists expiry_test_cache (id int primary key, expires_at timestamptz)");
        jdbc.execute("delete from expiry_test_cache");
        jdbc.update("insert into expiry_test_cache values (1, now() - interval '1 second'), (2, now() + interval '1 hour')");

        purger.purgeAll();

        assertEquals(1, jdbc.queryForObject("select count(*) from expiry_test_cache", Integer.class));
    }

    @Test
    void rejectsTableOrColumnNamesThatCouldInjectSql() {
        assertThrows(IllegalArgumentException.class, () -> new ExpiringTable("x; drop table y", "expires_at"));
        assertThrows(IllegalArgumentException.class, () -> new ExpiringTable("x", "a b"));
        assertThrows(IllegalArgumentException.class, () -> new ExpiringTable("X", "expires_at"));
    }
}
