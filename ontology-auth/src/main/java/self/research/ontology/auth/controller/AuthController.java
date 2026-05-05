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
import self.research.ontology.auth.service.AuditService;
import self.research.ontology.auth.service.EmailService;
import self.research.ontology.auth.service.RateLimitService;
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
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Value("${app.admin.email:admin@example.com}")
    private String adminEmail;

    public AuthController(AuthenticationManager authenticationManager,
                          UserDetailsService userDetailsService,
                          JwtUtil jwtUtil,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          EmailService emailService,
                          RateLimitService rateLimitService,
                          AuditService auditService) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
    }

    /**
     * Create default admin user if it doesn't exist
     * Admin password MUST be set via environment variable in production
     */
    @PostConstruct
    public void createDefaultUsers() {
        if (userRepository.findByUsername("admin").isEmpty()) {
            if (adminPassword == null || adminPassword.isBlank()) {
                log.error("⚠️  ADMIN_PASSWORD environment variable not set!");
                log.error("⚠️  Default admin user NOT created.");
                log.error("⚠️  Set ADMIN_PASSWORD to create admin user.");
                return;
            }

            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode(adminPassword));
            admin.setEmail(adminEmail);
            admin.setRoles(Set.of("ROLE_ADMIN"));
            admin.setEnabled(true);
            userRepository.save(admin);

            log.warn("✓ Default admin user created");
            log.warn("⚠️  CHANGE THE ADMIN PASSWORD IMMEDIATELY!");
        }
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

        // Rate limiting (5 requests per minute)
        Bucket bucket = rateLimitService.resolveBucket(clientIp);
        if (!bucket.tryConsume(1)) {
            auditService.logRateLimitHit(request.getUsername(), clientIp);
            return ResponseEntity.status(429).body(Map.of(
                "error", "Too many login attempts. Please try again later."
            ));
        }

        // Check if user exists (support login with username or email)
        Optional<User> userOpt = userRepository.findByUsername(request.getUsername());
        if (userOpt.isEmpty()) {
            // Try finding by email if username not found
            userOpt = userRepository.findByEmail(request.getUsername());
        }
        
        if (userOpt.isEmpty()) {
            auditService.logLoginFailure(request.getUsername(), clientIp, "User not found");
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid username/email or password"
            ));
        }

        User user = userOpt.get();

        // Check if account is locked
        if (user.isAccountLocked()) {
            auditService.logAccountLocked(request.getUsername(), clientIp);
            return ResponseEntity.status(423).body(Map.of(
                "error", "Account is locked due to too many failed attempts. Please try again later."
            ));
        }

        // Authenticate
        try {
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                    user.getUsername(), // Use actual username from DB
                    request.getPassword()
                )
            );

            // Reset failed attempts on successful authentication
            user.resetFailedAttempts();
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);

        } catch (AuthenticationException e) {
            // Increment failed attempts
            user.incrementFailedAttempts();
            
            // Lock account after 5 failed attempts
            if (user.getFailedLoginAttempts() >= 5) {
                user.lockAccount(15); // 15 minutes lockout
                userRepository.save(user);
                auditService.logAccountLocked(request.getUsername(), clientIp);
                return ResponseEntity.status(423).body(Map.of(
                    "error", "Account locked due to too many failed attempts. Please try again in 15 minutes."
                ));
            }

            userRepository.save(user);
            auditService.logLoginFailure(request.getUsername(), clientIp, e.getMessage());
            
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid username or password"
            ));
        }

        // Check if account is verified
        if (!user.isEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Account not verified. Please check your email to verify your account."
            ));
        }

        // Generate JWT token
        UserDetails userDetails = userDetailsService.loadUserByUsername(request.getUsername());
        String jwt = jwtUtil.generateToken(userDetails, user.getEmail(), user.getId(), user.getSubscriptionPlanName());

        // Clear rate limit on successful login
        rateLimitService.clearLimit(clientIp);

        auditService.logLoginSuccess(request.getUsername(), clientIp);

        // Check if user is admin
        boolean isAdmin = user.getRoles().contains("ROLE_ADMIN");

        return ResponseEntity.ok(Map.of(
            "jwt", jwt,
            "username", user.getUsername(),
            "email", user.getEmail(),
            "roles", user.getRoles(),
            "isAdmin", isAdmin
        ));
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
            String email = jwtUtil.extractEmail(token);
            
            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            User user = userOpt.get();
            UserDetails userDetails = userDetailsService.loadUserByUsername(email);
            
            // Generate fresh token with current subscription plan
            String newJwt = jwtUtil.generateToken(userDetails, user.getEmail(), user.getId(), user.getSubscriptionPlanName());
            
            boolean isAdmin = user.getRoles().contains("ROLE_ADMIN");
            
            return ResponseEntity.ok(Map.of(
                "jwt", newJwt,
                "username", user.getUsername(),
                "email", user.getEmail(),
                "roles", user.getRoles(),
                "isAdmin", isAdmin,
                "subscriptionPlan", user.getSubscriptionPlanName()
            ));
        } catch (Exception e) {
            log.error("Token refresh failed", e);
            return ResponseEntity.status(401).body(Map.of("error", "Failed to refresh session: " + e.getMessage()));
        }
    }

    /**
     * Signup endpoint
     * Features: Email verification, password validation, audit logging
     */
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@Valid @RequestBody SignupRequest request) {
        // Check if username already exists
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Username already exists"
            ));
        }

        // Check if email already exists
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Email already registered"
            ));
        }

        // Create new user
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        
        // Don't set role on signup - will be set after deployment selection
        user.setRoles(new HashSet<>());
        
        user.setEnabled(false); // Require email verification

        // Generate verification token (expires in 24 hours)
        String verificationToken = UUID.randomUUID().toString();
        user.setVerificationToken(verificationToken);
        user.setVerificationTokenExpiry(LocalDateTime.now().plusHours(24));

        userRepository.save(user);

        // Send verification email
        try {
            emailService.sendVerificationEmail(user.getEmail(), verificationToken);
        } catch (Exception e) {
            log.error("Failed to send verification email", e);
            // Don't fail registration if email fails
        }

        auditService.logSignup(request.getUsername(), request.getEmail());

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
                "error", "Verification token has expired. Please register again."
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

        boolean isAdmin = user.getRoles().contains("ROLE_ADMIN");

        return ResponseEntity.ok(Map.of(
            "message", "Email verified successfully!",
            "jwt", jwt,
            "username", user.getUsername(),
            "email", user.getEmail(),
            "roles", user.getRoles(),
            "isAdmin", isAdmin
        ));
    }

    /**
     * Resend verification email
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<?> resendVerification(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }

        // Always return success to prevent email enumeration
        userRepository.findByEmail(email).ifPresent(user -> {
            if (!user.isEnabled()) {
                String verificationToken = UUID.randomUUID().toString();
                user.setVerificationToken(verificationToken);
                user.setVerificationTokenExpiry(LocalDateTime.now().plusHours(24));
                userRepository.save(user);

                try {
                    emailService.sendVerificationEmail(user.getEmail(), verificationToken);
                } catch (Exception e) {
                    log.error("Failed to resend verification email", e);
                }
            }
        });

        return ResponseEntity.ok(Map.of(
            "message", "If the email exists and is not yet verified, a new verification link has been sent."
        ));
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

        Optional<User> userOpt = userRepository.findByEmail(request.getEmail());
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
            
            Optional<User> userOpt = userRepository.findByUsername(username);
            if (userOpt.isEmpty()) {
                log.error("User not found: {}", username);
                return ResponseEntity.notFound().build();
            }
            
            User user = userOpt.get();
            log.info("Current user roles: {}", user.getRoles());
            
            // Set role based on deployment type
            // Self-hosted: Users get admin access (they own the instance)
            // Cloud: Users get regular user access (shared multi-tenant environment)
            if ("self-hosted".equalsIgnoreCase(deploymentType)) {
                user.setRoles(Set.of("ROLE_USER", "ROLE_ADMIN"));
                log.info("Setting self-hosted roles (ROLE_USER, ROLE_ADMIN) for user: {}", username);
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
