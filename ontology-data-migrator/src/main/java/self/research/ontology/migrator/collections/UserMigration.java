package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.UniqueRule;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.util.List;

public final class UserMigration implements CollectionMigration {

    @Override
    public String name() {
        return "users";
    }

    @Override
    public String sourceCollection() {
        return "users";
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.lowerField("lower(email)", "email"), UniqueRule.field("username", "username"));
    }

    @Override
    public List<Write> map(Document d) {
        Write.Builder row = Write.upsert("app_user", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("username", Values.required(d, "username"))
                .put("email", Values.required(d, "email"))
                .put("password", Values.text(d, "password"))
                .put("roles", Values.listOrEmpty(d, "roles"))
                .put("enabled", Values.bool(d, "enabled", false))
                .put("failed_login_attempts", Values.integer(d, "failedLoginAttempts", 0))
                .put("auto_renew_enabled", Values.bool(d, "autoRenewEnabled", true))
                .put("has_used_free_trial", Values.bool(d, "hasUsedFreeTrial", false));
        text(row, d, "verification_token", "verificationToken");
        text(row, d, "password_reset_token", "passwordResetToken");
        text(row, d, "last_opened_project_id", "lastOpenedProjectId");
        text(row, d, "last_opened_project_name", "lastOpenedProjectName");
        text(row, d, "last_opened_file_id", "lastOpenedFileId");
        text(row, d, "last_opened_file_name", "lastOpenedFileName");
        text(row, d, "stripe_customer_id", "stripeCustomerId");
        text(row, d, "stripe_subscription_id", "stripeSubscriptionId");
        text(row, d, "subscription_status", "subscriptionStatus");
        text(row, d, "subscription_plan_id", "subscriptionPlanId");
        text(row, d, "subscription_plan_name", "subscriptionPlanName");
        text(row, d, "billing_interval", "billingInterval");
        text(row, d, "pending_billing_interval", "pendingBillingInterval");
        text(row, d, "pending_checkout_session_id", "pendingCheckoutSessionId");
        time(row, d, "verification_token_expiry", "verificationTokenExpiry");
        time(row, d, "password_reset_token_expiry", "passwordResetTokenExpiry");
        time(row, d, "lockout_end_time", "lockoutEndTime");
        time(row, d, "created_at", "createdAt");
        time(row, d, "updated_at", "updatedAt");
        time(row, d, "last_login_at", "lastLoginAt");
        time(row, d, "subscription_current_period_end", "subscriptionCurrentPeriodEnd");
        time(row, d, "subscription_canceled_at", "subscriptionCanceledAt");
        time(row, d, "first_subscription_at", "firstSubscriptionAt");
        time(row, d, "pending_billing_interval_date", "pendingBillingIntervalDate");
        time(row, d, "pending_checkout_created_at", "pendingCheckoutCreatedAt");
        return List.of(row.build());
    }

    private static void text(Write.Builder row, Document d, String column, String field) {
        row.put(column, Values.text(d, field));
    }

    private static void time(Write.Builder row, Document d, String column, String field) {
        row.put(column, Values.time(d, field));
    }
}
