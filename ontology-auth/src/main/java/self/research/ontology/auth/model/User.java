package self.research.ontology.auth.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

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

    // Password is optional for OIDC users
    @Pattern(
        regexp = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=!])(?=\\S+$).{8,}$",
        message = "Password must contain at least 8 characters, one uppercase, one lowercase, one number, and one special character"
    )
    private String password;
    
    // Display name
    private String name;

    private Set<String> roles = new HashSet<>();
    private boolean enabled = false;
    private boolean emailVerified = false;

    // OIDC provider (keycloak) - null for local users
    private String oidcProvider;

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
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public boolean isEmailVerified() {
        return emailVerified;
    }
    
    public void setEmailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }
    
    public String getOidcProvider() {
        return oidcProvider;
    }
    
    public void setOidcProvider(String oidcProvider) {
        this.oidcProvider = oidcProvider;
    }
    
    // Convenience method for lastLogin (alias)
    public void setLastLogin(LocalDateTime lastLogin) {
        this.lastLoginAt = lastLogin;
    }
    
    public LocalDateTime getLastLogin() {
        return this.lastLoginAt;
    }
}