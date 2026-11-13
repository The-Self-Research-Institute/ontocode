package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Instants;

import java.time.Instant;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcProjectWriteLeaseRepository implements ProjectWriteLeaseRepository {

    private final JdbcTemplate jdbc;

    public JdbcProjectWriteLeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Acquisition tryAcquire(String projectId, String ownerToken, String holder, Instant now, Instant expiresAt) {
        List<Boolean> rows = jdbc.queryForList("insert into project_write_lease (project_id, owner_token, holder, acquired_at, expires_at) "
                        + "values (?, ?, ?, ?, ?) on conflict (project_id) do update set owner_token = excluded.owner_token, "
                        + "holder = excluded.holder, acquired_at = excluded.acquired_at, expires_at = excluded.expires_at "
                        + "where project_write_lease.expires_at < ? returning (xmax = 0)",
                Boolean.class, projectId, ownerToken, holder, Instants.toDb(now), Instants.toDb(expiresAt), Instants.toDb(now));
        if (rows.isEmpty()) {
            return Acquisition.HELD;
        }
        return Boolean.TRUE.equals(rows.get(0)) ? Acquisition.ACQUIRED : Acquisition.TOOK_OVER;
    }

    @Override
    public boolean renew(String projectId, String ownerToken, Instant expiresAt) {
        return jdbc.update("update project_write_lease set expires_at = ? where project_id = ? and owner_token = ?",
                Instants.toDb(expiresAt), projectId, ownerToken) > 0;
    }

    @Override
    public boolean release(String projectId, String ownerToken) {
        return jdbc.update("delete from project_write_lease where project_id = ? and owner_token = ?",
                projectId, ownerToken) > 0;
    }
}
