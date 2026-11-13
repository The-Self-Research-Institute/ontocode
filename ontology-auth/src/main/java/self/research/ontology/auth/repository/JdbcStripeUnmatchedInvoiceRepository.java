package self.research.ontology.auth.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.auth.model.StripeUnmatchedInvoice;
import self.research.ontology.common.db.sql.Instants;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcStripeUnmatchedInvoiceRepository implements StripeUnmatchedInvoiceRepository {

    private static final RowMapper<StripeUnmatchedInvoice> ROWS = (rs, i) -> StripeUnmatchedInvoice.builder()
            .id(rs.getString("id"))
            .invoiceId(rs.getString("invoice_id"))
            .stripeCustomerId(rs.getString("stripe_customer_id"))
            .stripeSubscriptionId(rs.getString("stripe_subscription_id"))
            .customerEmail(rs.getString("customer_email"))
            .discoveredEmail(rs.getString("discovered_email"))
            .amountPaid(AuthSql.readLong(rs, "amount_paid"))
            .amountDue(AuthSql.readLong(rs, "amount_due"))
            .currency(rs.getString("currency"))
            .hostedInvoiceUrl(rs.getString("hosted_invoice_url"))
            .recordedAt(Instants.readLocal(rs, "recorded_at"))
            .status(rs.getString("status"))
            .build();

    private final JdbcTemplate jdbc;

    public JdbcStripeUnmatchedInvoiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(StripeUnmatchedInvoice v) {
        LocalDateTime recorded = v.recordedAt() != null ? v.recordedAt() : LocalDateTime.now();
        AuthSql.update(jdbc,
                "insert into stripe_unmatched_invoice (id, invoice_id, stripe_customer_id, stripe_subscription_id, "
                        + "customer_email, discovered_email, amount_paid, amount_due, currency, hosted_invoice_url, "
                        + "recorded_at, status) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                AuthSql.idOrNew(v.id()), v.invoiceId(), v.stripeCustomerId(), v.stripeSubscriptionId(),
                v.customerEmail(), v.discoveredEmail(), v.amountPaid(), v.amountDue(), v.currency(),
                v.hostedInvoiceUrl(), AuthSql.db(recorded), v.status() != null ? v.status() : "unmatched");
    }

    @Override
    public List<StripeUnmatchedInvoice> findRecent(int limit) {
        return jdbc.query("select * from stripe_unmatched_invoice order by recorded_at desc, id desc limit ?", ROWS,
                limit);
    }
}
