package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.document.AssistantAuditEntryDocument;

import java.time.Duration;
import java.time.Instant;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcAssistantAuditRepository implements AssistantAuditRepository {

    public static final Duration RETENTION = Duration.ofDays(90);

    private static final SqlUpsert UPSERT = SqlUpsert.into("assistant_audit").key("id")
            .columns("id", "actor", "project_id", "session_id", "group_id", "operation", "source_revision", "provider",
                    "model", "outcome", "error_code", "detail", "created_at", "expires_at")
            .build();

    private final JdbcTemplate jdbc;

    public JdbcAssistantAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AssistantAuditEntryDocument save(AssistantAuditEntryDocument e) {
        if (e.getId() == null || e.getId().isBlank()) {
            e.setId(Ids.newObjectId());
        }
        Instant expiresAt = e.getCreatedAt() == null ? null : e.getCreatedAt().plus(RETENTION);
        EditorSql.update(jdbc, UPSERT.sql(), e.getId(), e.getActor(), e.getProjectId(), e.getSessionId(),
                e.getGroupId(), e.getOperation(), e.getSourceRevision(), e.getProvider(), e.getModel(),
                e.getOutcome(), e.getErrorCode(), e.getDetail(), Instants.toDb(e.getCreatedAt()),
                Instants.toDb(expiresAt));
        return e;
    }
}
