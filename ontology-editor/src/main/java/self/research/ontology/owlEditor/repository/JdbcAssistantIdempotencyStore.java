package self.research.ontology.owlEditor.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.owlEditor.document.AssistantIdempotencyRecordDocument;
import self.research.ontology.owlEditor.document.AssistantIdempotencyRecordDocument.State;
import self.research.ontology.owlEditor.service.AssistantIdempotencyStore;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcAssistantIdempotencyStore implements AssistantIdempotencyStore {

    private static final String COLUMNS = "id, actor, method, path, body_hash, state, owner_token, response_status, "
            + "response_content_type, response_body, created_at, updated_at, expires_at";

    private static final RowMapper<AssistantIdempotencyRecordDocument> MAPPER = (rs, i) -> read(rs);

    private final JdbcTemplate jdbc;

    public JdbcAssistantIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryInsert(AssistantIdempotencyRecordDocument r) {
        return EditorSql.update(jdbc, "insert into assistant_idempotency (" + COLUMNS
                + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (id) do nothing", values(r)) > 0;
    }

    @Override
    public Optional<AssistantIdempotencyRecordDocument> find(String id) {
        return EditorSql.first(EditorSql.query(jdbc, "select " + COLUMNS + " from assistant_idempotency where id = ?",
                MAPPER, id));
    }

    @Override
    public boolean tryTakeOver(String id, Instant staleInFlightBefore, Instant now,
                               AssistantIdempotencyRecordDocument r) {
        return EditorSql.update(jdbc, "update assistant_idempotency set actor = ?, method = ?, path = ?, "
                + "body_hash = ?, state = ?, owner_token = ?, response_status = ?, response_content_type = ?, "
                + "response_body = ?, created_at = ?, updated_at = ?, expires_at = ? where id = ? "
                + "and (expires_at < ? or (state = 'IN_FLIGHT' and updated_at < ?))",
                r.getActor(), r.getMethod(), r.getPath(), r.getBodyHash(), name(r.getState()), r.getOwnerToken(),
                r.getResponseStatus(), r.getResponseContentType(), r.getResponseBody(),
                Instants.toDb(r.getCreatedAt()), Instants.toDb(r.getUpdatedAt()), Instants.toDb(r.getExpiresAt()),
                id, Instants.toDb(now), Instants.toDb(staleInFlightBefore)) > 0;
    }

    @Override
    public boolean complete(String id, String ownerToken, int status, String contentType, String body, Instant now) {
        boolean stored = EditorSql.update(jdbc, "update assistant_idempotency set state = 'COMPLETED', "
                + "response_status = ?, response_content_type = ?, response_body = ?, updated_at = ? "
                + "where id = ? and owner_token = ? and state = 'IN_FLIGHT'", status, contentType, body,
                Instants.toDb(now), id, ownerToken) > 0;
        if (!stored) {
            log.warn("[AssistantIdempotency] Record {} was taken over before its response could be stored", id);
        }
        return stored;
    }

    @Override
    public void abandon(String id, String ownerToken) {
        EditorSql.update(jdbc, "delete from assistant_idempotency where id = ? and owner_token = ? "
                + "and state = 'IN_FLIGHT'", id, ownerToken);
    }

    private static String name(State state) {
        return state == null ? null : state.name();
    }

    private static Object[] values(AssistantIdempotencyRecordDocument r) {
        return new Object[] {r.getId(), r.getActor(), r.getMethod(), r.getPath(), r.getBodyHash(), name(r.getState()),
                r.getOwnerToken(), r.getResponseStatus(), r.getResponseContentType(), r.getResponseBody(),
                Instants.toDb(r.getCreatedAt()), Instants.toDb(r.getUpdatedAt()), Instants.toDb(r.getExpiresAt())};
    }

    private static AssistantIdempotencyRecordDocument read(ResultSet rs) throws SQLException {
        String state = rs.getString("state");
        return AssistantIdempotencyRecordDocument.builder()
                .id(rs.getString("id"))
                .actor(rs.getString("actor"))
                .method(rs.getString("method"))
                .path(rs.getString("path"))
                .bodyHash(rs.getString("body_hash"))
                .state(state == null ? null : State.valueOf(state))
                .ownerToken(rs.getString("owner_token"))
                .responseStatus(EditorSql.readInt(rs, "response_status"))
                .responseContentType(rs.getString("response_content_type"))
                .responseBody(rs.getString("response_body"))
                .createdAt(Instants.read(rs, "created_at"))
                .updatedAt(Instants.read(rs, "updated_at"))
                .expiresAt(Instants.read(rs, "expires_at"))
                .build();
    }
}
