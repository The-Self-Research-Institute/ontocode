package self.research.ontology.auth.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import self.research.ontology.auth.model.User;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.common.db.sql.SqlUpsert;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcUserRepository implements UserRepository {

    private static final SqlUpsert UPSERT = SqlUpsert.into("app_user")
            .key("id")
            .columns("id", "username", "email", "password", "roles", "enabled", "verification_token",
                    "verification_token_expiry", "password_reset_token", "password_reset_token_expiry",
                    "failed_login_attempts", "lockout_end_time", "created_at", "updated_at", "last_login_at",
                    "last_opened_project_id", "last_opened_project_name", "last_opened_file_id",
                    "last_opened_file_name", "stripe_customer_id", "stripe_subscription_id", "subscription_status",
                    "subscription_plan_id", "subscription_plan_name", "billing_interval",
                    "subscription_current_period_end", "auto_renew_enabled", "subscription_canceled_at",
                    "has_used_free_trial", "first_subscription_at", "pending_billing_interval",
                    "pending_billing_interval_date", "pending_checkout_session_id", "pending_checkout_created_at")
            .build();

    private static final RowMapper<User> ROWS = (rs, i) -> {
        User u = new User();
        u.setId(rs.getString("id"));
        u.setUsername(rs.getString("username"));
        u.setEmail(rs.getString("email"));
        u.setPassword(rs.getString("password"));
        u.setRoles(AuthSql.readSet(rs, "roles"));
        u.setEnabled(rs.getBoolean("enabled"));
        u.setVerificationToken(rs.getString("verification_token"));
        u.setVerificationTokenExpiry(Instants.readLocal(rs, "verification_token_expiry"));
        u.setPasswordResetToken(rs.getString("password_reset_token"));
        u.setPasswordResetTokenExpiry(Instants.readLocal(rs, "password_reset_token_expiry"));
        u.setFailedLoginAttempts(rs.getInt("failed_login_attempts"));
        u.setLockoutEndTime(Instants.readLocal(rs, "lockout_end_time"));
        u.setCreatedAt(Instants.readLocal(rs, "created_at"));
        u.setUpdatedAt(Instants.readLocal(rs, "updated_at"));
        u.setLastLoginAt(Instants.readLocal(rs, "last_login_at"));
        u.setLastOpenedProjectId(rs.getString("last_opened_project_id"));
        u.setLastOpenedProjectName(rs.getString("last_opened_project_name"));
        u.setLastOpenedFileId(rs.getString("last_opened_file_id"));
        u.setLastOpenedFileName(rs.getString("last_opened_file_name"));
        u.setStripeCustomerId(rs.getString("stripe_customer_id"));
        u.setStripeSubscriptionId(rs.getString("stripe_subscription_id"));
        u.setSubscriptionStatus(rs.getString("subscription_status"));
        u.setSubscriptionPlanId(rs.getString("subscription_plan_id"));
        u.setSubscriptionPlanName(rs.getString("subscription_plan_name"));
        u.setBillingInterval(rs.getString("billing_interval"));
        u.setSubscriptionCurrentPeriodEnd(Instants.readLocal(rs, "subscription_current_period_end"));
        u.setAutoRenewEnabled(rs.getBoolean("auto_renew_enabled"));
        u.setSubscriptionCanceledAt(Instants.readLocal(rs, "subscription_canceled_at"));
        u.setHasUsedFreeTrial(rs.getBoolean("has_used_free_trial"));
        u.setFirstSubscriptionAt(Instants.readLocal(rs, "first_subscription_at"));
        u.setPendingBillingInterval(rs.getString("pending_billing_interval"));
        u.setPendingBillingIntervalDate(Instants.readLocal(rs, "pending_billing_interval_date"));
        u.setPendingCheckoutSessionId(rs.getString("pending_checkout_session_id"));
        u.setPendingCheckoutCreatedAt(Instants.readLocal(rs, "pending_checkout_created_at"));
        u.setSubscriptionCreationLockedAt(Instants.readLocal(rs, "subscription_creation_locked_at"));
        return u;
    };

    private final JdbcTemplate jdbc;

    public JdbcUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private Optional<User> one(String where, Object arg) {
        return AuthSql.first(jdbc.query("select * from app_user where " + where + " limit 1", ROWS, arg));
    }

    @Override
    public Optional<User> findById(String id) {
        return id == null ? Optional.empty() : one("id = ?", id);
    }

    @Override
    public List<User> findAll() {
        return jdbc.query("select * from app_user order by id", ROWS);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return username == null ? Optional.empty() : one("username = ?", username);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return email == null ? Optional.empty() : one("email = ?", email);
    }

    @Override
    public Optional<User> findByEmailIgnoreCase(String email) {
        return email == null ? Optional.empty() : one("lower(email) = lower(?)", email);
    }

    @Override
    public Optional<User> findByStripeCustomerId(String stripeCustomerId) {
        return stripeCustomerId == null ? Optional.empty() : one("stripe_customer_id = ?", stripeCustomerId);
    }

    @Override
    public Optional<User> findByStripeSubscriptionId(String stripeSubscriptionId) {
        return stripeSubscriptionId == null ? Optional.empty() : one("stripe_subscription_id = ?", stripeSubscriptionId);
    }

    @Override
    public Optional<User> findByVerificationToken(String verificationToken) {
        return verificationToken == null ? Optional.empty() : one("verification_token = ?", verificationToken);
    }

    @Override
    public Optional<User> findByPasswordResetToken(String passwordResetToken) {
        return passwordResetToken == null ? Optional.empty() : one("password_reset_token = ?", passwordResetToken);
    }

    @Override
    public User save(User u) {
        u.setId(AuthSql.idOrNew(u.getId()));
        AuthSql.update(jdbc, UPSERT.sql(), u.getId(), u.getUsername(), u.getEmail(), u.getPassword(),
                AuthSql.textList(u.getRoles()), u.isEnabled(), u.getVerificationToken(),
                AuthSql.db(u.getVerificationTokenExpiry()), u.getPasswordResetToken(),
                AuthSql.db(u.getPasswordResetTokenExpiry()), u.getFailedLoginAttempts(),
                AuthSql.db(u.getLockoutEndTime()), AuthSql.db(u.getCreatedAt()), AuthSql.db(u.getUpdatedAt()),
                AuthSql.db(u.getLastLoginAt()), u.getLastOpenedProjectId(), u.getLastOpenedProjectName(),
                u.getLastOpenedFileId(), u.getLastOpenedFileName(), u.getStripeCustomerId(),
                u.getStripeSubscriptionId(), u.getSubscriptionStatus(), u.getSubscriptionPlanId(),
                u.getSubscriptionPlanName(), u.getBillingInterval(), AuthSql.db(u.getSubscriptionCurrentPeriodEnd()),
                u.isAutoRenewEnabled(), AuthSql.db(u.getSubscriptionCanceledAt()), u.isHasUsedFreeTrial(),
                AuthSql.db(u.getFirstSubscriptionAt()), u.getPendingBillingInterval(),
                AuthSql.db(u.getPendingBillingIntervalDate()), u.getPendingCheckoutSessionId(),
                AuthSql.db(u.getPendingCheckoutCreatedAt()));
        return u;
    }

    @Override
    public void delete(User user) {
        if (user != null && user.getId() != null) {
            jdbc.update("delete from app_user where id = ?", user.getId());
        }
    }

    @Override
    public int incrementFailedLoginAttempts(String userId) {
        List<Integer> rows = jdbc.queryForList(
                "update app_user set failed_login_attempts = failed_login_attempts + 1 where id = ? "
                        + "returning failed_login_attempts", Integer.class, userId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    @Override
    public boolean tryClaimSubscriptionCreationLock(String userId, LocalDateTime now, LocalDateTime staleBefore) {
        return AuthSql.update(jdbc,
                "update app_user set subscription_creation_locked_at = ? where id = ? "
                        + "and (subscription_creation_locked_at is null or subscription_creation_locked_at < ?)",
                AuthSql.db(now), userId, AuthSql.db(staleBefore)) > 0;
    }

    @Override
    public void releaseSubscriptionCreationLock(String userId) {
        jdbc.update("update app_user set subscription_creation_locked_at = null where id = ?", userId);
    }

    @Override
    public boolean markPastDueIfCurrentSubscription(String userId, String subscriptionId) {
        return AuthSql.update(jdbc,
                "update app_user set subscription_status = 'past_due' where id = ? "
                        + "and stripe_subscription_id = ? and subscription_status is distinct from 'canceled'",
                userId, subscriptionId) > 0;
    }
}
