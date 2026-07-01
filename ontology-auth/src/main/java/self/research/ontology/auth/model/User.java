package self.research.ontology.auth.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Document(collection = "users")
public class User {

    @Id
    private String id;

    @NotBlank(message = "Username is required")
    @Indexed(unique = true)
    private String username;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Indexed(unique = true)
    private String email;

    @NotBlank(message = "Password is required")
    private String password;

    private Set<String> roles = new HashSet<>();
    private boolean enabled = false;

    // Email verification
    private String verificationToken;
    private LocalDateTime verificationTokenExpiry;

    // Password reset
    private String passwordResetToken;
    private LocalDateTime passwordResetTokenExpiry;

    // Account lockout
    private int failedLoginAttempts = 0;
    private LocalDateTime lockoutEndTime;

    // Audit fields
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastLoginAt;

    // Last opened context (for auto-restore across devices)
    private String lastOpenedProjectId;
    private String lastOpenedProjectName;
    private String lastOpenedFileId;
    private String lastOpenedFileName;

    // Stripe Billing
    private String stripeCustomerId;
    private String stripeSubscriptionId;
    private String subscriptionStatus;   // active, trialing, past_due, canceled, unpaid
    private String subscriptionPlanId;   // Stripe price ID
    private String subscriptionPlanName; // FREE, PRO, ENTERPRISE
    private String billingInterval;      // monthly, yearly
    private LocalDateTime subscriptionCurrentPeriodEnd;
    private boolean autoRenewEnabled = true;
    private LocalDateTime subscriptionCanceledAt;
    /**
     * Set to true the first time this account creates ANY paid subscription
     * (whether or not the trial completed). Stripe itself does not track
     * trial eligibility per customer, so we enforce one-trial-per-account
     * server-side: if this is true, {@code setTrialPeriodDays} is omitted on
     * subsequent subscription creations. Bug #39 / #40.
     */
    private boolean hasUsedFreeTrial = false;
    private LocalDateTime firstSubscriptionAt;
    // Pending billing interval downgrade — annual→monthly queued for next renewal
    private String pendingBillingInterval;
    private LocalDateTime pendingBillingIntervalDate;
    // Pending checkout lock — cleared once checkout.session.completed fires
    private String pendingCheckoutSessionId;
    private LocalDateTime pendingCheckoutCreatedAt;

    // Constructors
    public User() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // Account lockout methods
    public boolean isAccountLocked() {
        if (lockoutEndTime == null) {
            return false;
        }
        if (LocalDateTime.now().isBefore(lockoutEndTime)) {
            return true;
        }
        // Auto-unlock if time has passed
        lockoutEndTime = null;
        failedLoginAttempts = 0;
        return false;
    }

    public void lockAccount(int minutes) {
        this.lockoutEndTime = LocalDateTime.now().plusMinutes(minutes);
    }

    public void incrementFailedAttempts() {
        this.failedLoginAttempts++;
    }

    public void resetFailedAttempts() {
        this.failedLoginAttempts = 0;
        this.lockoutEndTime = null;
    }

    // Token expiration methods
    public boolean isVerificationTokenExpired() {
        return verificationTokenExpiry != null && 
               LocalDateTime.now().isAfter(verificationTokenExpiry);
    }

    public boolean isPasswordResetTokenExpired() {
        return passwordResetTokenExpiry != null && 
               LocalDateTime.now().isAfter(passwordResetTokenExpiry);
    }

    public void clearVerificationToken() {
        this.verificationToken = null;
        this.verificationTokenExpiry = null;
    }

    public void clearPasswordResetToken() {
        this.passwordResetToken = null;
        this.passwordResetTokenExpiry = null;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getVerificationToken() {
        return verificationToken;
    }

    public void setVerificationToken(String verificationToken) {
        this.verificationToken = verificationToken;
    }

    public LocalDateTime getVerificationTokenExpiry() {
        return verificationTokenExpiry;
    }

    public void setVerificationTokenExpiry(LocalDateTime verificationTokenExpiry) {
        this.verificationTokenExpiry = verificationTokenExpiry;
    }

    public String getPasswordResetToken() {
        return passwordResetToken;
    }

    public void setPasswordResetToken(String passwordResetToken) {
        this.passwordResetToken = passwordResetToken;
    }

    public LocalDateTime getPasswordResetTokenExpiry() {
        return passwordResetTokenExpiry;
    }

    public void setPasswordResetTokenExpiry(LocalDateTime passwordResetTokenExpiry) {
        this.passwordResetTokenExpiry = passwordResetTokenExpiry;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public void setFailedLoginAttempts(int failedLoginAttempts) {
        this.failedLoginAttempts = failedLoginAttempts;
    }

    public LocalDateTime getLockoutEndTime() {
        return lockoutEndTime;
    }

    public void setLockoutEndTime(LocalDateTime lockoutEndTime) {
        this.lockoutEndTime = lockoutEndTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public String getLastOpenedProjectId() { return lastOpenedProjectId; }
    public void setLastOpenedProjectId(String lastOpenedProjectId) { this.lastOpenedProjectId = lastOpenedProjectId; }

    public String getLastOpenedProjectName() { return lastOpenedProjectName; }
    public void setLastOpenedProjectName(String lastOpenedProjectName) { this.lastOpenedProjectName = lastOpenedProjectName; }

    public String getLastOpenedFileId() { return lastOpenedFileId; }
    public void setLastOpenedFileId(String lastOpenedFileId) { this.lastOpenedFileId = lastOpenedFileId; }

    public String getLastOpenedFileName() { return lastOpenedFileName; }
    public void setLastOpenedFileName(String lastOpenedFileName) { this.lastOpenedFileName = lastOpenedFileName; }

    public String getStripeCustomerId() { return stripeCustomerId; }
    public void setStripeCustomerId(String stripeCustomerId) { this.stripeCustomerId = stripeCustomerId; }

    public String getStripeSubscriptionId() { return stripeSubscriptionId; }
    public void setStripeSubscriptionId(String stripeSubscriptionId) { this.stripeSubscriptionId = stripeSubscriptionId; }

    public String getSubscriptionStatus() { return subscriptionStatus; }
    public void setSubscriptionStatus(String subscriptionStatus) { this.subscriptionStatus = subscriptionStatus; }

    public String getSubscriptionPlanId() { return subscriptionPlanId; }
    public void setSubscriptionPlanId(String subscriptionPlanId) { this.subscriptionPlanId = subscriptionPlanId; }

    public String getSubscriptionPlanName() { return subscriptionPlanName; }
    public void setSubscriptionPlanName(String subscriptionPlanName) { this.subscriptionPlanName = subscriptionPlanName; }

    public String getBillingInterval() { return billingInterval; }
    public void setBillingInterval(String billingInterval) { this.billingInterval = billingInterval; }

    public String getPendingBillingInterval() { return pendingBillingInterval; }
    public void setPendingBillingInterval(String pendingBillingInterval) { this.pendingBillingInterval = pendingBillingInterval; }

    public LocalDateTime getPendingBillingIntervalDate() { return pendingBillingIntervalDate; }
    public void setPendingBillingIntervalDate(LocalDateTime pendingBillingIntervalDate) { this.pendingBillingIntervalDate = pendingBillingIntervalDate; }

    public LocalDateTime getSubscriptionCurrentPeriodEnd() { return subscriptionCurrentPeriodEnd; }
    public void setSubscriptionCurrentPeriodEnd(LocalDateTime subscriptionCurrentPeriodEnd) { this.subscriptionCurrentPeriodEnd = subscriptionCurrentPeriodEnd; }

    public boolean isAutoRenewEnabled() { return autoRenewEnabled; }
    public void setAutoRenewEnabled(boolean autoRenewEnabled) { this.autoRenewEnabled = autoRenewEnabled; }

    public LocalDateTime getSubscriptionCanceledAt() { return subscriptionCanceledAt; }
    public void setSubscriptionCanceledAt(LocalDateTime subscriptionCanceledAt) { this.subscriptionCanceledAt = subscriptionCanceledAt; }

    public boolean isHasUsedFreeTrial() { return hasUsedFreeTrial; }
    public void setHasUsedFreeTrial(boolean hasUsedFreeTrial) { this.hasUsedFreeTrial = hasUsedFreeTrial; }

    public LocalDateTime getFirstSubscriptionAt() { return firstSubscriptionAt; }
    public void setFirstSubscriptionAt(LocalDateTime firstSubscriptionAt) { this.firstSubscriptionAt = firstSubscriptionAt; }

    public String getPendingCheckoutSessionId() { return pendingCheckoutSessionId; }
    public void setPendingCheckoutSessionId(String pendingCheckoutSessionId) { this.pendingCheckoutSessionId = pendingCheckoutSessionId; }

    public LocalDateTime getPendingCheckoutCreatedAt() { return pendingCheckoutCreatedAt; }
    public void setPendingCheckoutCreatedAt(LocalDateTime pendingCheckoutCreatedAt) { this.pendingCheckoutCreatedAt = pendingCheckoutCreatedAt; }
}