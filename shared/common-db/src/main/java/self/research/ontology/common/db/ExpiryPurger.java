package self.research.ontology.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

public class ExpiryPurger {

    private static final Logger LOG = LoggerFactory.getLogger(ExpiryPurger.class);

    private final JdbcTemplate jdbc;
    private final ObjectProvider<ExpiringTable> tables;
    private final int batchSize;

    public ExpiryPurger(JdbcTemplate jdbc, ObjectProvider<ExpiringTable> tables, int batchSize) {
        this.jdbc = jdbc;
        this.tables = tables;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ontocode.db.expiry.interval-ms:60000}", initialDelayString = "${ontocode.db.expiry.initial-delay-ms:30000}")
    public void purgeAll() {
        List<ExpiringTable> all = tables.orderedStream().toList();
        for (ExpiringTable table : all) {
            try {
                purge(table);
            } catch (RuntimeException e) {
                LOG.warn("Expiry purge failed for {}: {}", table.table(), e.getMessage());
            }
        }
    }

    public int purge(ExpiringTable table) {
        String sql = "delete from " + table.table() + " where ctid = any(array(select ctid from "
                + table.table() + " where " + table.column() + " < now() limit ?))";
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update(sql, batchSize);
            total += deleted;
        } while (deleted >= batchSize);
        if (total > 0) {
            LOG.debug("Purged {} expired rows from {}", total, table.table());
        }
        return total;
    }
}
