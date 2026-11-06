package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcAssistantSessionRepository implements AssistantSessionRepository {

    public static final Duration PURGE_GRACE = Duration.ofHours(24);

    private static final String[] COLUMN_LIST = {"id", "project_id", "user_email", "document_path", "action_type",
            "action_context", "provider", "model", "draft", "draft_user_id", "pinned_revision", "status",
            "retrieval_attempts_remaining", "token_budget_remaining", "deadline_at", "created_at", "updated_at",
            "expires_at"};

    private static final String COLUMNS = String.join(", ", COLUMN_LIST);

    private static final SqlUpsert UPSERT = SqlUpsert.into("assistant_session").key("id").columns(COLUMN_LIST).build();

    private static final String LIVE = " and (expires_at is null or expires_at > now())";

    private static final RowMapper<AssistantSessionDocument> MAPPER = (rs, i) -> read(rs);

    private final JdbcTemplate jdbc;

    public JdbcAssistantSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AssistantSessionDocument save(AssistantSessionDocument s) {
        if (s.getId() == null || s.getId().isBlank()) {
            s.setId(Ids.newObjectId());
        }
        Instant purgeAt = s.getExpiresAt() == null ? null : s.getExpiresAt().plus(PURGE_GRACE);
        EditorSql.update(jdbc, UPSERT.sql(), s.getId(), s.getProjectId(), s.getUserEmail(), s.getDocumentPath(),
                s.getActionType(), s.getActionContext(), s.getProvider(), s.getModel(), s.isDraft(),
                s.getDraftUserId(), s.getPinnedRevision(), s.getStatus() == null ? null : s.getStatus().name(),
                s.getRetrievalAttemptsRemaining(), s.getTokenBudgetRemaining(), Instants.toDb(s.getExpiresAt()),
                Instants.toDb(s.getCreatedAt()), Instants.toDb(s.getUpdatedAt()), Instants.toDb(purgeAt));
        return s;
    }

    @Override
    public Optional<AssistantSessionDocument> findById(String id) {
        return EditorSql.first(EditorSql.query(jdbc, select("id = ?" + LIVE), MAPPER, id));
    }

    @Override
    public Optional<AssistantSessionDocument> findByIdAndUserEmail(String id, String userEmail) {
        return EditorSql.first(EditorSql.query(jdbc, select("id = ? and user_email = ?" + LIVE), MAPPER, id,
                userEmail));
    }

    @Override
    public long countActive(String userEmail, Instant now) {
        Long count = jdbc.queryForObject("select count(*) from assistant_session where user_email = ? "
                + "and status = 'ACTIVE' and deadline_at > ?", Long.class, userEmail, Instants.toDb(now));
        return count == null ? 0 : count;
    }

    @Override
    public Optional<Instant> soonestActiveDeadline(String userEmail, Instant now) {
        Instant soonest = jdbc.queryForObject("select min(deadline_at) from assistant_session "
                + "where user_email = ? and status = 'ACTIVE' and deadline_at > ?",
                (rs, i) -> Instants.fromDb(rs.getObject(1, java.time.OffsetDateTime.class)), userEmail,
                Instants.toDb(now));
        return Optional.ofNullable(soonest);
    }

    @Override
    public boolean tryConsumeRetrievalAttempt(String id, Instant now) {
        return EditorSql.update(jdbc, "update assistant_session set retrieval_attempts_remaining = "
                + "retrieval_attempts_remaining - 1, updated_at = ? where id = ? and status = 'ACTIVE' "
                + "and retrieval_attempts_remaining > 0", Instants.toDb(now), id) > 0;
    }

    @Override
    public boolean tryConsumeTokenBudget(String id, int tokens, Instant now) {
        return EditorSql.update(jdbc, "update assistant_session set token_budget_remaining = "
                + "token_budget_remaining - ?, updated_at = ? where id = ? and status = 'ACTIVE' "
                + "and token_budget_remaining >= ?", tokens, Instants.toDb(now), id, tokens) > 0;
    }

    @Override
    public void updateStatus(String id, AssistantSessionStatus status, Instant now) {
        EditorSql.update(jdbc, "update assistant_session set status = ?, updated_at = ? where id = ?", status.name(),
                Instants.toDb(now), id);
    }

    private static String select(String where) {
        return "select " + COLUMNS + " from assistant_session where " + where;
    }

    private static AssistantSessionDocument read(ResultSet rs) throws SQLException {
        String status = rs.getString("status");
        return AssistantSessionDocument.builder()
                .id(rs.getString("id"))
                .projectId(rs.getString("project_id"))
                .userEmail(rs.getString("user_email"))
                .documentPath(rs.getString("document_path"))
                .actionType(rs.getString("action_type"))
                .actionContext(rs.getString("action_context"))
                .provider(rs.getString("provider"))
                .model(rs.getString("model"))
                .draft(rs.getBoolean("draft"))
                .draftUserId(rs.getString("draft_user_id"))
                .pinnedRevision(EditorSql.readLong(rs, "pinned_revision"))
                .status(status == null ? null : AssistantSessionStatus.valueOf(status))
                .retrievalAttemptsRemaining(EditorSql.readInt(rs, "retrieval_attempts_remaining"))
                .tokenBudgetRemaining(EditorSql.readInt(rs, "token_budget_remaining"))
                .expiresAt(Instants.read(rs, "deadline_at"))
                .createdAt(Instants.read(rs, "created_at"))
                .updatedAt(Instants.read(rs, "updated_at"))
                .build();
    }
}
