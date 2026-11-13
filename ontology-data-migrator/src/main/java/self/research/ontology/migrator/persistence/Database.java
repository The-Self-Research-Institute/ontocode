package self.research.ontology.migrator.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.DiskBlobStore;
import self.research.ontology.common.db.StoredFiles;
import self.research.ontology.migrator.core.Options;

import javax.sql.DataSource;
import java.nio.file.Path;

public final class Database implements AutoCloseable {

    private final HikariDataSource dataSource;

    public Database(Options options) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(options.pgUrl);
        ds.setUsername(options.pgUser);
        ds.setPassword(options.pgPassword);
        ds.setMaximumPoolSize(3);
        ds.setPoolName("migrator");
        this.dataSource = ds;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public void migrateSchema() {
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    public DiskBlobStore blobStore(String root) {
        return new DiskBlobStore(new StoredFiles(new JdbcTemplate(dataSource), new ObjectMapper()), Path.of(root), 0L);
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
