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

        // Check if user exists
        Optional<User> userOpt = userRepository.findByUsername(request.getUsername());
        if (userOpt.isEmpty()) {
            auditService.logLoginFailure(request.getUsername(), clientIp, "User not found");
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid username or password"
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
                    request.getUsername(),
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
        String jwt = jwtUtil.generateToken(userDetails, user.getEmail());

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
        user.setRoles(Set.of("ROLE_USER"));
        user.setEnabled(true); // Enable immediately for development (skip email verification)

        // Generate verification token (expires in 24 hours)
        String verificationToken = UUID.randomUUID().toString();
        user.setVerificationToken(verificationToken);
        user.setVerificationTokenExpiry(LocalDateTime.now().plusHours(24));

        userRepository.save(user);

        // Skip sending verification email for development
        // try {
        //     emailService.sendVerificationEmail(user.getEmail(), verificationToken);
        // } catch (Exception e) {
        //     log.error("Failed to send verification email", e);
        //     // Don't fail registration if email fails
        // }

        auditService.logSignup(request.getUsername(), request.getEmail());

        // Generate JWT token for immediate login
        UserDetails userDetails = userDetailsService.loadUserByUsername(request.getUsername());
        String jwt = jwtUtil.generateToken(userDetails, user.getEmail());

        // Check if user is admin
        boolean isAdmin = user.getRoles().contains("ROLE_ADMIN");

        return ResponseEntity.ok(Map.of(
            "jwt", jwt,
            "username", user.getUsername(),
            "email", user.getEmail(),
            "roles", user.getRoles(),
            "isAdmin", isAdmin,
            "message", "Registration successful!"
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
        userRepository.save(user);

        auditService.logEmailVerified(user.getUsername());

        return ResponseEntity.ok(Map.of(
            "message", "Email verified successfully! You can now log in."
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

        // Always return success to prevent email enumeration
        userRepository.findByEmail(request.getEmail()).ifPresent(user -> {
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
            }

            auditService.logPasswordResetRequest(user.getUsername(), clientIp);
        });

        return ResponseEntity.ok(Map.of(
            "message", "If the email exists in our system, a password reset link has been sent."
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
        userRepository.save(user);

        auditService.logPasswordResetSuccess(user.getUsername());

        return ResponseEntity.ok(Map.of(
            "message", "Password reset successfully! You can now log in with your new password."
        ));
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
     * Get client IP address (handles proxy headers)
     */
    private String getClientIP(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0];
    }
}