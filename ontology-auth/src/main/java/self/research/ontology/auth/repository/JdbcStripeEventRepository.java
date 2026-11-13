package self.research.ontology.auth.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.auth.model.StripeEvent;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcStripeEventRepository implements StripeEventRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("stripe_event")
            .key("id")
            .columns("id", "stripe_event_id", "event_type", "status", "error_message", "received_at", "processed_at")
            .build();

    private static final RowMapper<StripeEvent> ROWS = (rs, i) -> {
        StripeEvent e = new StripeEvent();
        e.setId(rs.getString("id"));
        e.setStripeEventId(rs.getString("stripe_event_id"));
        e.setEventType(rs.getString("event_type"));
        e.setStatus(rs.getString("status"));
        e.setErrorMessage(rs.getString("error_message"));
        e.setReceivedAt(Instants.readLocal(rs, "received_at"));
        e.setProcessedAt(Instants.readLocal(rs, "processed_at"));
        return e;
    };

    private final JdbcTemplate jdbc;

    public JdbcStripeEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StripeEvent insert(StripeEvent e) {
        e.setId(AuthSql.idOrNew(e.getId()));
        int inserted = AuthSql.update(jdbc,
                "insert into stripe_event (id, stripe_event_id, event_type, status, error_message, received_at, "
                        + "processed_at) values (?, ?, ?, ?, ?, ?, ?) on conflict do nothing",
                e.getId(), e.getStripeEventId(), e.getEventType(), e.getStatus(), e.getErrorMessage(),
                AuthSql.db(e.getReceivedAt()), AuthSql.db(e.getProcessedAt()));
        if (inserted == 0) {
            throw new DuplicateKeyException("Stripe event " + e.getStripeEventId() + " already recorded");
        }
        return e;
    }

    @Override
    public StripeEvent save(StripeEvent e) {
        e.setId(AuthSql.idOrNew(e.getId()));
        AuthSql.update(jdbc, UPSERT.sql(), e.getId(), e.getStripeEventId(), e.getEventType(), e.getStatus(),
                e.getErrorMessage(), AuthSql.db(e.getReceivedAt()), AuthSql.db(e.getProcessedAt()));
        return e;
    }

    @Override
    public Optional<StripeEvent> findByStripeEventId(String stripeEventId) {
        return AuthSql.first(jdbc.query("select * from stripe_event where stripe_event_id = ?", ROWS,
                stripeEventId));
    }

    @Override
    public boolean existsByStripeEventId(String stripeEventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from stripe_event where stripe_event_id = ?)", Boolean.class,
                stripeEventId));
    }

    @Override
    public List<StripeEvent> findByEventTypeAndStatusSince(String eventType, String status,
                                                           LocalDateTime receivedSince) {
        return jdbc.query("select * from stripe_event where event_type = ? and status = ? and received_at >= ? "
                + "order by received_at, id", ROWS, eventType, status, AuthSql.db(receivedSince));
    }
}
