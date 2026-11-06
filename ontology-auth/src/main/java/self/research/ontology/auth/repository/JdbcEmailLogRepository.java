package self.research.ontology.auth.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.auth.model.EmailLog;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlLists;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcEmailLogRepository implements EmailLogRepository {

    private static final RowMapper<EmailLog> ROWS = (rs, i) -> EmailLog.builder()
            .key(rs.getString("log_key"))
            .type(rs.getString("type"))
            .status(rs.getString("status"))
            .kind(rs.getString("kind"))
            .daysBefore(AuthSql.readInt(rs, "days_before"))
            .periodEnd(Instants.readLocal(rs, "period_end"))
            .email(rs.getString("email"))
            .userId(rs.getString("user_id"))
            .username(rs.getString("username"))
            .stripeInvoiceId(rs.getString("stripe_invoice_id"))
            .stripeCustomerId(rs.getString("stripe_customer_id"))
            .stripeSubscriptionId(rs.getString("stripe_subscription_id"))
            .amountPaid(AuthSql.readLong(rs, "amount_paid"))
            .currency(rs.getString("currency"))
            .createdAt(Instants.readLocal(rs, "created_at"))
            .sentAt(Instants.readLocal(rs, "sent_at"))
            .failedAt(Instants.readLocal(rs, "failed_at"))
            .errorMessage(rs.getString("error_message"))
            .build();

    private final JdbcTemplate jdbc;

    public JdbcEmailLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean reserve(EmailLog l) {
        LocalDateTime created = l.createdAt() != null ? l.createdAt() : LocalDateTime.now();
        return AuthSql.update(jdbc,
                "insert into email_log (id, log_key, type, status, kind, days_before, period_end, email, user_id, "
                        + "username, stripe_invoice_id, stripe_customer_id, stripe_subscription_id, amount_paid, "
                        + "currency, created_at) values (?, ?, ?, 'sending', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "on conflict (log_key) do nothing",
                Ids.newObjectId(), l.key(), l.type(), l.kind(), l.daysBefore(), AuthSql.db(l.periodEnd()), l.email(),
                l.userId(), l.username(), l.stripeInvoiceId(), l.stripeCustomerId(), l.stripeSubscriptionId(),
                l.amountPaid(), l.currency(), AuthSql.db(created)) > 0;
    }

    @Override
    public boolean existsWithStatus(String key, String... statuses) {
        if (statuses == null || statuses.length == 0) {
            return false;
        }
        List<Object> args = new ArrayList<>();
        args.add(key);
        args.addAll(List.of(statuses));
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from email_log where log_key = ? "
                + "and status in (" + SqlLists.placeholders(statuses.length) + "))", Boolean.class, args.toArray()));
    }

    @Override
    public void markSent(String key, LocalDateTime sentAt) {
        AuthSql.update(jdbc, "update email_log set status = 'sent', sent_at = ? where log_key = ?",
                AuthSql.db(sentAt), key);
    }

    @Override
    public void markFailed(String key, String errorMessage, LocalDateTime failedAt) {
        AuthSql.update(jdbc,
                "update email_log set status = 'failed', failed_at = ?, error_message = ? where log_key = ?",
                AuthSql.db(failedAt), errorMessage, key);
    }

    @Override
    public Optional<EmailLog> findByKey(String key) {
        return AuthSql.first(jdbc.query("select * from email_log where log_key = ?", ROWS, key));
    }
}
