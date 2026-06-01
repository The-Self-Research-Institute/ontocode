package self.research.ontology.auth.controller;

import io.github.bucket4j.Bucket;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.auth.dto.AuthRequests.*;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.WorkspaceRepository;
import self.research.ontology.auth.service.AuditService;
import self.research.ontology.auth.service.EmailService;
import self.research.ontology.auth.service.RateLimitService;
import self.research.ontology.auth.service.SystemSettingsService;
import self.research.ontology.auth.util.JwtUtil;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final SystemSettingsService systemSettingsService;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Value("${app.admin.email:admin@example.com}")
    private String adminEmail;

    @Value("${app.email.enabled:true}")
    private boolean emailEnabled;

    /**
     * Comma-separated list of allowed email domains for login/signup during restricted testing.
     * Empty = allow all. Example: "coretopia.com,example.com"
     */
    @Value("${app.allowed.email.domains:}")
    private String allowedEmailDomains;

    public AuthController(AuthenticationManager authenticationManager,
                          UserDetailsService userDetailsService,
                          JwtUtil jwtUtil,
                          UserRepository userRepository,
                          WorkspaceRepository workspaceRepository,
                          PasswordEncoder passwordEncoder,
                          EmailService emailService,
                          RateLimitService rateLimitService,
                          AuditService auditService,
                          SystemSettingsService systemSettingsService) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
        this.systemSettingsService = systemSettingsService;
    }

    private boolean isDomainAllowed(String email) {
        if (allowedEmailDomains == null || allowedEmailDomains.isBlank()) {
            return true; // no restriction
        }
        String lower = email.toLowerCase(Locale.ROOT);
        for (String domain : allowedEmailDomains.split(",")) {
            if (lower.endsWith("@" + domain.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create default admin user if it doesn't exist
     * Admin password MUST be set via environment variable in production
     */
    @PostConstruct
    public void createDefaultUsers() {
        // Ensure the designated admin account has ROLE_ADMIN
        userRepository.findByEmailIgnoreCase(adminEmail).ifPresentOrElse(admin -> {
            boolean needsSave = false;
            if (admin.getRoles() == null || !admin.getRoles().contains("ROLE_ADMIN")) {
                admin.setRoles(Set.of("ROLE_ADMIN"));
                needsSave = true;
                log.warn("✓ Admin user roles corrected to ROLE_ADMIN (email={})", adminEmail);
            }
            if (admin.getSubscriptionPlanName() == null) {
                admin.setSubscriptionPlanName("FREE");
                needsSave = true;
            }
            if (needsSave) userRepository.save(admin);
        }, () -> {
            if (adminPassword == null || adminPassword.isBlank()) {
                log.error("⚠️  ADMIN_PASSWORD environment variable not set — admin user NOT created.");
                return;
            }
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode(adminPassword));
            admin.setEmail(adminEmail);
            admin.setRoles(Set.of("ROLE_ADMIN"));
            admin.setSubscriptionPlanName("FREE");
            admin.setEnabled(true);
            userRepository.save(admin);
            log.warn("✓ Default admin user created (email={})", adminEmail);
        });

        // Desktop mode: strip ROLE_ADMIN from any user who is NOT the designated admin email.
        // This corrects accounts that were incorrectly promoted before this fix.
        if (emailEnabled) return; // cloud mode — don't touch roles
        userRepository.findAll().forEach(user -> {
            if (!user.getEmail().equalsIgnoreCase(adminEmail)
                    && user.getRoles() != null
                    && user.getRoles().contains("ROLE_ADMIN")) {
                user.setRoles(Set.of("ROLE_USER"));
                userRepository.save(user);
                log.warn("✓ Desktop: removed ROLE_ADMIN from non-admin user (email={})", user.getEmail());
            }
        });
    }

    /**
     * Login endpoint
     * Features: Rate limiting, account lockout, audit logging
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        String clientIp = getClientIP(httpRequest);
        String loginIdentifier = request.getUsername() == null ? "" : request.getUsername().trim();

        // Rate limiting (5 requests per minute)
        Bucket bucket = rateLimitService.resolveBucket(clientIp);
        if (!bucket.tryConsume(1)) {
            auditService.logRateLimitHit(loginIdentifier, clientIp);
            return ResponseEntity.status(429).body(Map.of(
                "error", "Too many login attempts. Please try again later."
            ));
        }

        // Check if user exists (support login with username or email)
        Optional<User> userOpt = userRepository.findByUsername(loginIdentifier);
        if (userOpt.isEmpty()) {
            // Try finding by email if username not found
            userOpt = userRepository.findByEmailIgnoreCase(loginIdentifier);
        }
        
        if (userOpt.isEmpty()) {
            auditService.logLoginFailure(loginIdentifier, clientIp, "User not found");
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid username/email or password"
            ));
        }

        User user = userOpt.get();

        // Maintenance mode — checked first, before any account-state errors.
        // Bypass list is the DB-managed maintenanceAllowedDomains (admin UI).
        if (systemSettingsService.isBlockedByMaintenance(user.getEmail())) {
            log.warn("Login blocked — maintenance mode active for: {}", user.getEmail());
            return ResponseEntity.status(503).body(Map.of(
                "error", "The system is currently under maintenance. Please try again later.",
                "maintenance", true
            ));
        }

        // Check if account is locked
        if (user.isAccountLocked()) {
            auditService.logAccountLocked(loginIdentifier, clientIp);
            return ResponseEntity.status(423).body(Map.of(
                "error", "Account is locked due to too many failed attempts. Please try again later."
            ));
        }

        // Check if account is verified before password auth. Skip in desktop mode (email disabled).
        if (!user.isEnabled()) {
            if (!emailEnabled) {
                // Desktop mode: auto-verify the account silently
                user.setEnabled(true);
                user.setVerificationToken(null);
                user.setVerificationTokenExpiry(null);
                userRepository.save(user);
            } else {
                auditService.logLoginFailure(loginIdentifier, clientIp, "Account not verified");
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Account not verified. Please check your email to verify your account."
                ));
            }
        }

        // Authenticate directly against the selected user's stored BCrypt hash.
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            // Increment failed attempts
            user.incrementFailedAttempts();
            
            // Lock account after 5 failed attempts
            if (user.getFailedLoginAttempts() >= 5) {
                user.lockAccount(15); // 15 minutes lockout
                userRepository.save(user);
                auditService.logAccountLocked(loginIdentifier, clientIp);
                return ResponseEntity.status(423).body(Map.of(
                    "error", "Account locked due to too many failed attempts. Please try again in 15 minutes."
                ));
            }

            userRepository.save(user);
            auditService.logLoginFailure(loginIdentifier, clientIp, "Bad credentials");
            
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid username or password"
            ));
        }

        // Reset failed attempts on successful authentication
        user.resetFailedAttempts();
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        // Enterprise domain bypass: auto-grant ENTERPRISE to user account and all their workspaces
        if (systemSettingsService.isEnterpriseDomain(user.getEmail())) {
            if (!"ENTERPRISE".equalsIgnoreCase(user.getSubscriptionPlanName())) {
                user.setSubscriptionPlanName("ENTERPRISE");
                user.setSubscriptionStatus("active");
                userRepository.save(user);
                log.info("Enterprise domain bypass: set user plan to ENTERPRISE for {}", user.getEmail());
            }
            workspaceRepository.findByOwnerId(user.getId()).stream()
                .filter(ws -> !Boolean.TRUE.equals(ws.getIsDeleted()))
                .filter(ws -> !"ENTERPRISE".equalsIgnoreCase(ws.getSubscriptionPlan()))
                .forEach(ws -> {
                    ws.setSubscriptionPlan("ENTERPRISE");
                    ws.setCollaborationEnabled(true);
                    workspaceRepository.save(ws);
                    log.info("Enterprise domain bypass: upgraded workspace {} for {}", ws.getWorkspaceId(), user.getEmail());
                });
        }

        // Generate JWT token
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String jwt = jwtUtil.generateToken(userDetails, user.getEmail(), user.getId(), user.getSubscriptionPlanName());

        // Clear rate limit on successful login
        rateLimitService.clearLimit(clientIp);

        auditService.logLoginSuccess(loginIdentifier, clientIp);

        Set<String> loginRoles = user.getRoles() != null ? user.getRoles() : Set.of();
        boolean isAdmin = loginRoles.contains("ROLE_ADMIN");

        Map<String, Object> loginResp = new HashMap<>();
        loginResp.put("jwt", jwt);
        loginResp.put("username", user.getUsername());
        loginResp.put("email", user.getEmail());
        loginResp.put("roles", loginRoles);
        loginResp.put("isAdmin", isAdmin);
        loginResp.put("enterpriseDomainBypass", systemSettingsService.isEnterpriseDomain(user.getEmail()));
        return ResponseEntity.ok(loginResp);
    }

    /**
     * Refresh JWT token endpoint
     * Generates a fresh token with current roles and subscription status
     */
    @GetMapping("/refresh")
    public ResponseEntity<?> refreshToken(HttpServletRequest request) {
        try {
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
            }

            String token = authHeader.substring(7);
            // Allow slightly-expired tokens: race condition between the 15s subscription poll
            // and the 60s client-side expiry check means the token may have just expired.
            String email = jwtUtil.extractEmailAllowExpired(token);
            
            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            User user = userOpt.get();

            // Maintenance mode: block token refresh.
            // Bypass list is the DB-managed maintenanceAllowedDomains (admin UI).
            if (systemSettingsService.isBlockedByMaintenance(user.getEmail())) {
                log.warn("Token refresh blocked — maintenance mode active for: {}", user.getEmail());
                return ResponseEntity.status(503).body(Map.of(
                    "error", "The system is currently under maintenance. Please try again later.",
                    "maintenance", true
                ));
            }

            UserDetails userDetails = userDetailsService.loadUserByUsername(email);

            Set<String> roles = user.getRoles() != null ? user.getRoles() : Set.of();
            String planName = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName() : "FREE";
            boolean isAdmin = roles.contains("ROLE_ADMIN");

            // Generate fresh token with current subscription plan
            String newJwt = jwtUtil.generateToken(userDetails, user.getEmail(), user.getId(), planName);

            Map<String, Object> resp = new HashMap<>();
            resp.put("jwt", newJwt);
            resp.put("username", user.getUsername());
            resp.put("email", user.getEmail());
            resp.put("roles", roles);
            resp.put("isAdmin", isAdmin);
            resp.put("subscriptionPlan", planName);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("Token refresh failed", e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ResponseEntity.status(401).body(Map.of("error", "Failed to refresh session: " + msg));
        }
    }

    /**
     * Signup endpoint
     * Features: Email verification, password validation, audit logging
     */
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@Valid @RequestBody SignupRequest request) {
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        String email = request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase(Locale.ROOT);

        // Check if username already exists
        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Username already exists"
            ));
        }

        // Check if email already exists
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Email already registered"
            ));
        }

        // Maintenance mode check — bypass list is the DB-managed maintenanceAllowedDomains (admin UI).
        if (systemSettingsService.isBlockedByMaintenance(email)) {
            log.warn("Signup blocked — maintenance mode active for: {}", email);
            return ResponseEntity.status(403).body(Map.of(
                "error", "Registration is currently restricted to authorised users only. Please contact support.",
                "maintenance", true
            ));
        }

        // Create new user
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(email);
        
        // Don't set role on signup - will be set after deployment selection
        user.setRoles(new HashSet<>());
        
        if (emailEnabled) {
            user.setEnabled(false); // Require email verification
            String verificationToken = UUID.randomUUID().toString();
            user.setVerificationToken(verificationToken);
            user.setVerificationTokenExpiry(LocalDateTime.now().plusHours(24));
            userRepository.save(user);
            try {
                emailService.sendVerificationEmail(user.getEmail(), verificationToken);
            } catch (Exception e) {
                log.error("Failed to send verification email", e);
            }
        } else {
            // Desktop mode: auto-verify, no email needed
            user.setEnabled(true);
            userRepository.save(user);
        }

        auditService.logSignup(username, email);

        return ResponseEntity.ok(Map.of(
            "message", "Registration successful! Please check your email to verify your account.",
            "requiresVerification", true,
            "email", user.getEmail()
        ));
    }

    /**
     * Email verification endpoint
     */
    @GetMapping("/verify")
    public ResponseEntity<?> verify(@RequestParam("token") String token) {
        Optional<User> userOpt = userRepository.findByVerificationToken(token);

        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid verification token"
            ));
        }

        User user = userOpt.get();

        // Check if token is expired
        if (user.isVerificationTokenExpired()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Verification token has expired. Please request a new verification link.",
                "email", user.getEmail()
            ));
        }

        // Enable account
        user.setEnabled(true);
        user.clearVerificationToken();
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        auditService.logEmailVerified(user.getEmail());

        // Generate JWT for auto-login
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
        String jwt = jwtUtil.generateToken(userDetails, user.getEmail(), user.getId(), user.getSubscriptionPlanName());

        Set<String> verifyRoles = user.getRoles() != null ? user.getRoles() : Set.of();
        boolean isAdmin = verifyRoles.contains("ROLE_ADMIN");

        Map<String, Object> verifyResp = new HashMap<>();
        verifyResp.put("message", "Email verified successfully!");
        verifyResp.put("jwt", jwt);
        verifyResp.put("username", user.getUsername());
        verifyResp.put("email", user.getEmail());
        verifyResp.put("roles", verifyRoles);
        verifyResp.put("isAdmin", isAdmin);
        return ResponseEntity.ok(verifyResp);
    }

    /**
     * Resend verification email
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@RequestBody Map<String, String> request) {
        String identifier = request.get("email") == null ? "" : request.get("email").trim();
        if (identifier == null || identifier.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email or username is required"));
        }

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(identifier.toLowerCase(Locale.ROOT))
                .or(() -> userRepository.findByUsername(identifier));

        if (userOpt.isEmpty()) {
            // Generic message to prevent email enumeration
            return ResponseEntity.ok(Map.of("message", "If the email exists and is not yet verified, a new verification link has been sent."));
        }

        User user = userOpt.get();

        if (user.isEnabled()) {
            return ResponseEntity.ok(Map.of(
                "message", "This account is already verified. Please sign in."
            ));
        }

        String verificationToken = UUID.randomUUID().toString();
        user.setVerificationToken(verificationToken);
        user.setVerificationTokenExpiry(LocalDateTime.now().plusHours(24));
        userRepository.save(user);

        try {
            emailService.sendVerificationEmail(user.getEmail(), verificationToken);
        } catch (Exception e) {
            log.error("Failed to resend verification email", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to send email. Please try again."));
        }

        return ResponseEntity.ok(Map.of("message", "Verification email sent. Please check your inbox."));
    }

    /**
     * Forgot password endpoint
     * Sends password reset email
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest
    ) {
        String clientIp = getClientIP(httpRequest);
        String email = request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase(Locale.ROOT);

        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "No account found with that email address."
            ));
        }

        User user = userOpt.get();

        // Generate reset token (expires in 1 hour)
        String resetToken = UUID.randomUUID().toString();
        user.setPasswordResetToken(resetToken);
        user.setPasswordResetTokenExpiry(LocalDateTime.now().plusHours(1));
        userRepository.save(user);

        // Send reset email
        try {
            emailService.sendPasswordResetEmail(user.getEmail(), resetToken);
        } catch (Exception e) {
            log.error("Failed to send password reset email", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to send reset email. Please try again."
            ));
        }

        auditService.logPasswordResetRequest(user.getUsername(), clientIp);

        return ResponseEntity.ok(Map.of(
            "message", "A password reset link has been sent to your email."
        ));
    }

    /**
     * Reset password endpoint
     */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        Optional<User> userOpt = userRepository.findByPasswordResetToken(request.getToken());

        if (userOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid password reset token"
            ));
        }

        User user = userOpt.get();

        // Check if token is expired
        if (user.isPasswordResetTokenExpired()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Password reset token has expired. Please request a new one."
            ));
        }

        // Update password
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.clearPasswordResetToken();
        user.resetFailedAttempts(); // Clear any lockouts
        // Also enable account in case it was never verified — user proved email ownership by receiving the reset link
        user.setEnabled(true);
        userRepository.save(user);

        auditService.logPasswordResetSuccess(user.getUsername());

        return ResponseEntity.ok(Map.of(
            "message", "Password reset successfully! You can now log in with your new password."
        ));
    }

    /**
     * Change password (authenticated endpoint)
     * Requires current password verification
     * Sends email notification and returns success response
     */
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @RequestHeader("Authorization") String authHeader) {
        try {
            // Extract email from JWT token (subject is now email)
            String token = authHeader.replace("Bearer ", "");
            String email = jwtUtil.extractEmail(token);
            
            log.info("Change password request for user email: {}", email);

            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "User not found"
                ));
            }

            User user = userOpt.get();

            // Verify current password
            if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
                log.warn("Invalid current password for user: {}", user.getUsername());
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Current password is incorrect"
                ));
            }

            // Ensure new password is different from current
            if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "New password must be different from current password"
                ));
            }

            // Update password
            user.setPassword(passwordEncoder.encode(request.getNewPassword()));
            user.resetFailedAttempts(); // Clear any lockouts
            userRepository.save(user);

            // Send email notification
            try {
                emailService.sendPasswordChangeEmail(user.getEmail(), user.getUsername());
                log.info("Password change notification email sent to: {}", user.getEmail());
            } catch (Exception e) {
                log.error("Failed to send password change email", e);
                // Don't fail the password change if email fails
            }

            auditService.logPasswordChange(user.getUsername());
            log.info("Password changed successfully for user: {}", user.getUsername());

            return ResponseEntity.ok(Map.of(
                "message", "Password changed successfully! You will be logged out for security."
            ));
        } catch (Exception e) {
            log.error("Error changing password", e);
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Failed to change password: " + e.getMessage()
            ));
        }
    }

    /**
     * Get user email by username
     * Used by other services to lookup user email
     */
    @GetMapping("/user/email")
    public ResponseEntity<?> getUserEmail(@RequestParam String username) {
        try {
            Optional<User> userOpt = userRepository.findByUsername(username);
            if (userOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            User user = userOpt.get();
            return ResponseEntity.ok(Map.of(
                "username", user.getUsername(),
                "email", user.getEmail()
            ));
        } catch (Exception e) {
            log.error("Failed to get user email", e);
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Update user role based on deployment type
     */
    @PutMapping("/update-role")
    public ResponseEntity<?> updateRole(@RequestBody Map<String, String> request) {
        try {
            String username = request.get("username");
            String deploymentType = request.get("deploymentType");
            
            log.info("Update role request - username: {}, deploymentType: {}", username, deploymentType);
            
            if (username == null || deploymentType == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Username and deploymentType are required"
                ));
            }
            
            Optional<User> userOpt = userRepository.findByUsername(username)
                    .or(() -> userRepository.findByEmail(username));
            if (userOpt.isEmpty()) {
                log.error("User not found by username or email: {}", username);
                return ResponseEntity.notFound().build();
            }
            
            User user = userOpt.get();
            log.info("Current user roles: {}", user.getRoles());
            
            Set<String> currentRoles = user.getRoles() != null ? user.getRoles() : new HashSet<>();
            boolean isDesignatedAdmin = user.getEmail().equalsIgnoreCase(adminEmail);
            if (isDesignatedAdmin && currentRoles.contains("ROLE_ADMIN")) {
                log.info("Skipping role update — user {} is designated admin", username);
            } else if ("self-hosted".equalsIgnoreCase(deploymentType)) {
                user.setRoles(Set.of("ROLE_USER"));
                log.info("Setting desktop roles (ROLE_USER) for user: {}", username);
            } else {
                user.setRoles(Set.of("ROLE_USER"));
                log.info("Setting cloud roles (ROLE_USER) for user: {}", username);
            }
            
            userRepository.save(user);
            log.info("User roles saved. New roles: {}", user.getRoles());
            
            // Generate new JWT token with updated roles
            try {
                UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
                String jwt = jwtUtil.generateToken(userDetails, user.getEmail(), user.getId(), user.getSubscriptionPlanName());
                
                boolean isAdmin = user.getRoles().contains("ROLE_ADMIN");
                
                Map<String, Object> response = new HashMap<>();
                response.put("jwt", jwt);
                response.put("username", user.getUsername());
                response.put("email", user.getEmail());
                response.put("roles", user.getRoles());
                response.put("isAdmin", isAdmin);
                response.put("message", "Role updated successfully");
                
                log.info("Successfully updated role for user: {} to deployment type: {}", username, deploymentType);
                
                return ResponseEntity.ok(response);
            } catch (Exception tokenError) {
                log.error("Failed to generate JWT token for user: {}", username, tokenError);
                throw new RuntimeException("Failed to generate authentication token: " + tokenError.getMessage());
            }
        } catch (Exception e) {
            log.error("Failed to update user role for username: {}", request.get("username"), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Failed to update role: " + e.getMessage()
            ));
        }
    }

    /**
     * Get client IP address (handles proxy headers)
     */
    private String getClientIP(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0];
    }

    /**
     * Get last opened project/file context for the authenticated user
     */
    @GetMapping("/last-opened")
    public ResponseEntity<?> getLastOpened(HttpServletRequest request) {
        try {
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
            }
            String token = authHeader.substring(7);
            String email = jwtUtil.extractEmail(token);
            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isEmpty()) return ResponseEntity.notFound().build();
            User user = userOpt.get();
            Map<String, Object> result = new HashMap<>();
            result.put("projectId", user.getLastOpenedProjectId());
            result.put("projectName", user.getLastOpenedProjectName());
            result.put("fileId", user.getLastOpenedFileId());
            result.put("fileName", user.getLastOpenedFileName());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get last-opened context", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Save last opened project/file context for the authenticated user
     */
    @PutMapping("/last-opened")
    public ResponseEntity<?> saveLastOpened(@RequestBody Map<String, String> body, HttpServletRequest request) {
        try {
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
            }
            String token = authHeader.substring(7);
            String email = jwtUtil.extractEmail(token);
            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isEmpty()) return ResponseEntity.notFound().build();
            User user = userOpt.get();
            user.setLastOpenedProjectId(body.get("projectId"));
            user.setLastOpenedProjectName(body.get("projectName"));
            user.setLastOpenedFileId(body.get("fileId"));
            user.setLastOpenedFileName(body.get("fileName"));
            userRepository.save(user);
            return ResponseEntity.ok(Map.of("status", "saved"));
        } catch (Exception e) {
            log.error("Failed to save last-opened context", e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
