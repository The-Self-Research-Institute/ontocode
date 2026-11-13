package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.document.ProjectRecoveryLockDocument;

import java.time.Instant;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcProjectRecoveryLockRepository implements ProjectRecoveryLockRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("project_recovery_lock")
            .key("project_id")
            .columns("project_id", "locked", "reason", "locked_at", "operation_id", "can_restore", "released_by",
                    "release_action", "released_at", "updated_at")
            .build();

    private static final RowMapper<ProjectRecoveryLockDocument> MAPPER = (rs, i) -> ProjectRecoveryLockDocument.builder()
            .id(rs.getString("project_id"))
            .locked(rs.getBoolean("locked"))
            .reason(rs.getString("reason"))
            .lockedAt(Instants.read(rs, "locked_at"))
            .operationId(rs.getString("operation_id"))
            .canRestore(rs.getBoolean("can_restore"))
            .releasedBy(rs.getString("released_by"))
            .releaseAction(rs.getString("release_action"))
            .releasedAt(Instants.read(rs, "released_at"))
            .updatedAt(Instants.read(rs, "updated_at"))
            .build();

    private final JdbcTemplate jdbc;

    public JdbcProjectRecoveryLockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProjectRecoveryLockDocument> findById(String projectId) {
        return EditorSql.first(jdbc.query("select * from project_recovery_lock where project_id = ?", MAPPER, projectId));
    }

    @Override
    public ProjectRecoveryLockDocument save(ProjectRecoveryLockDocument lock) {
        jdbc.update(UPSERT.sql(), lock.getId(), lock.isLocked(), lock.getReason(), Instants.toDb(lock.getLockedAt()),
                lock.getOperationId(), lock.isCanRestore(), lock.getReleasedBy(), lock.getReleaseAction(),
                Instants.toDb(lock.getReleasedAt()), Instants.toDb(lock.getUpdatedAt()));
        return lock;
    }

    @Override
    public boolean releaseIfLocked(String projectId, String actor, String action, Instant now) {
        return jdbc.update("update project_recovery_lock set locked = false, released_by = ?, release_action = ?, "
                + "released_at = ?, updated_at = ? where project_id = ? and locked", actor, action,
                Instants.toDb(now), Instants.toDb(now), projectId) > 0;
    }
}
