package self.research.ontology.auth.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    
    @Autowired
    private JavaMailSender mailSender;

    @Value("${app.base-url:http://localhost:8082}")
    private String baseUrl;

    @Value("${app.email.from:noreply@ontocode.com}")
    private String fromEmail;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    public EmailService() {
        log.info("✓ SMTP Email service initialized");
    }

    /**
     * Send invitation email to the invitee
     */
    public void sendInvitationEmail(self.research.ontology.auth.model.Invitation invitation) {
        // Generate both VS Code extension and webview dev server links
        String webviewLink = "http://localhost:3000/?token=" + invitation.getInvitationToken();
        // Use correct extension identifier: publisher.extensionName
        String vscodeInvitationLink = "vscode://self.ontocode-extension/invite?token=" + invitation.getInvitationToken();
        
        String htmlContent = String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body {
                        font-family: Arial, sans-serif;
                        line-height: 1.6;
                        color: #333;
                    }
                    .container {
                        max-width: 600px;
                        margin: 0 auto;
                        padding: 20px;
                        background-color: #f9f9f9;
                    }
                    .content {
                        background-color: white;
                        padding: 30px;
                        border-radius: 8px;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                    }
                    .button {
                        display: inline-block;
                        background-color: #8B5CF6;
                        color: white !important;
                        padding: 12px 24px;
                        text-decoration: none;
                        border-radius: 6px;
                        margin: 10px 5px;
                        font-weight: 600;
                    }
                    .button-secondary {
                        background-color: #6366F1;
                    }
                    .footer {
                        margin-top: 20px;
                        font-size: 12px;
                        color: #666;
                    }
                    .badge {
                        display: inline-block;
                        background-color: #e0e7ff;
                        color: #4338ca;
                        padding: 4px 12px;
                        border-radius: 12px;
                        font-size: 12px;
                        font-weight: 600;
                    }
                    .options {
                        text-align: center;
                        margin: 20px 0;
                    }
                    .info-box {
                        background-color: #f3f4f6;
                        padding: 15px;
                        border-radius: 6px;
                        margin: 15px 0;
                        border-left: 4px solid #8B5CF6;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="content">
                        <h1 style="color: #8B5CF6;">You're Invited to OntoCode!</h1>
                        <p><strong>%s</strong> (%s) has invited you to join the workspace <strong>"%s"</strong> on OntoCode.</p>
                        <p>OntoCode is a collaborative ontology editor that helps teams create and manage knowledge graphs together.</p>
                        <p>Your assigned role: <span class="badge">%s</span></p>
                        
                        <div class="info-box">
                            <strong>🎯 Choose how to accept:</strong>
                            <p style="margin: 10px 0 5px 0; font-size: 14px;">For the best experience with VS Code integration:</p>
                        </div>
                        
                        <div class="options">
                            <a href="%s" class="button" style="color: white; text-decoration: none;">🚀 Open in VS Code</a>
                            <br>
                            <a href="%s" class="button button-secondary" style="color: white; text-decoration: none;">🌐 Open in Webview</a>
                        </div>
                        
                        <div style="background-color: #f9fafb; padding: 15px; border-radius: 6px; margin: 20px 0;">
                            <p style="margin: 0 0 10px 0; font-size: 13px; color: #6b7280;"><strong>Note:</strong> If the button doesn't work, copy and paste this link into your browser:</p>
                            <p style="margin: 5px 0; font-size: 12px; word-break: break-all; color: #4b5563; font-family: monospace; background: white; padding: 8px; border-radius: 4px;">%s</p>
                            <p style="margin: 10px 0 0 0; font-size: 12px; color: #6b7280;">Or use this VS Code link (if you have VS Code installed):</p>
                            <p style="margin: 5px 0; font-size: 12px; word-break: break-all; color: #4b5563; font-family: monospace; background: white; padding: 8px; border-radius: 4px;">%s</p>
                        </div>
                        
                        <p class="footer">
                            This invitation will expire on %s. If you didn't expect this invitation, you can safely ignore this email.
                        </p>
                    </div>
                </div>
            </body>
            </html>
            """,
            invitation.getInvitedBy(),
            invitation.getInvitedByEmail(),
            invitation.getWorkspaceName(),
            invitation.getRole(),
            vscodeInvitationLink,
            webviewLink,
            webviewLink,
            vscodeInvitationLink,
            invitation.getExpiresAt().toString()
        );

        try {
            log.info("📧 Preparing to send invitation email to: {}", invitation.getInviteeEmail());
            log.info("📧 Webview link: {}", webviewLink);
            log.info("📧 VS Code invitation link: {}", vscodeInvitationLink);
            log.info("📧 From email: {}", fromEmail);
            
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromEmail, "OntoCode Team");
            helper.setTo(invitation.getInviteeEmail());
            helper.setSubject(String.format("You're invited to join %s on OntoCode", invitation.getWorkspaceName()));
            helper.setText(htmlContent, true);

            log.info("📧 Sending email via SMTP...");
            mailSender.send(message);
            log.info("✅ Invitation email sent successfully to: {}", invitation.getInviteeEmail());
        } catch (MessagingException e) {
            log.error("❌ Failed to send invitation email to: {}", invitation.getInviteeEmail(), e);
            log.error("❌ Error type: {}", e.getClass().getName());
            log.error("❌ Error message: {}", e.getMessage());
            if (e.getCause() != null) {
                log.error("❌ Caused by: {}", e.getCause().getMessage());
            }
            throw new RuntimeException("Failed to send invitation email", e);
        } catch (Exception e) {
            log.error("❌ Failed to send invitation email to: {}", invitation.getInviteeEmail(), e);
            throw new RuntimeException("Failed to send invitation email", e);
        }
    }

    /**
     * Send email verification link to user
     */
    public void sendVerificationEmail(String to, String token) {
        String verificationUrl = baseUrl + "/api/auth/verify?token=" + token;
        
        String htmlContent = String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body {
                        font-family: Arial, sans-serif;
                        line-height: 1.6;
                        color: #333;
                    }
                    .container {
                        max-width: 600px;
                        margin: 0 auto;
                        padding: 20px;
                        background-color: #f9f9f9;
                    }
                    .content {
                        background-color: white;
                        padding: 30px;
                        border-radius: 8px;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                    }
                    .button {
                        display: inline-block;
                        background-color: #8B5CF6;
                        color: white !important;
                        padding: 12px 24px;
                        text-decoration: none;
                        border-radius: 6px;
                        margin: 20px 0;
                    }
                    .footer {
                        margin-top: 20px;
                        font-size: 12px;
                        color: #666;
                    }
                    .code {
                        background-color: #f4f4f4;
                        padding: 10px;
                        border-radius: 4px;
                        font-family: monospace;
                        word-break: break-all;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="content">
                        <h1 style="color: #8B5CF6;">Welcome to OntoCode!</h1>
                        <p>Thank you for signing up. To complete your registration, please verify your email address by clicking the button below:</p>
                        <a href="%s" class="button">Verify Email Address</a>
                        <p>Or copy and paste this link into your browser:</p>
                        <div class="code">%s</div>
                        <p class="footer">
                            This link will expire in 24 hours. If you didn't create an account with OntoCode, you can safely ignore this email.
                        </p>
                    </div>
                </div>
            </body>
            </html>
            """, verificationUrl, verificationUrl);

        try {
            log.info("Sending verification email to: {}", to);
            
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromEmail, "OntoCode Team");
            helper.setTo(to);
            helper.setSubject("OntoCode: Please Verify Your Email Address");
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Verification email sent successfully to: {}", to);
        } catch (MessagingException e) {
            log.error("Failed to send verification email to: {}", to, e);
            throw new RuntimeException("Failed to send verification email", e);
        } catch (Exception e) {
            log.error("Failed to send verification email to: {}", to, e);
            throw new RuntimeException("Failed to send verification email", e);
        }
    }

    /**
     * Send password reset link to user
     */
    public void sendPasswordResetEmail(String to, String token) {
        String resetUrl = baseUrl + "/reset-password?token=" + token;
        
        String htmlContent = String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body {
                        font-family: Arial, sans-serif;
                        line-height: 1.6;
                        color: #333;
                    }
                    .container {
                        max-width: 600px;
                        margin: 0 auto;
                        padding: 20px;
                        background-color: #f9f9f9;
                    }
                    .content {
                        background-color: white;
                        padding: 30px;
                        border-radius: 8px;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                    }
                    .button {
                        display: inline-block;
                        background-color: #8B5CF6;
                        color: white !important;
                        padding: 12px 24px;
                        text-decoration: none;
                        border-radius: 6px;
                        margin: 20px 0;
                    }
                    .footer {
                        margin-top: 20px;
                        font-size: 12px;
                        color: #666;
                    }
                    .code {
                        background-color: #f4f4f4;
                        padding: 10px;
                        border-radius: 4px;
                        font-family: monospace;
                        word-break: break-all;
                    }
                    .warning {
                        background-color: #fff3cd;
                        border-left: 4px solid: #ffc107;
                        padding: 10px;
                        margin: 20px 0;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="content">
                        <h1 style="color: #8B5CF6;">Password Reset Request</h1>
                        <p>We received a request to reset your password. Click the button below to create a new password:</p>
                        <a href="%s" class="button">Reset Password</a>
                        <p>Or copy and paste this link into your browser:</p>
                        <div class="code">%s</div>
                        <div class="warning">
                            <strong>Security Notice:</strong> This link will expire in 1 hour. If you didn't request a password reset, please ignore this email and your password will remain unchanged.
                        </div>
                        <p class="footer">
                            If you're having trouble, contact support at support@ontocode.com
                        </p>
                    </div>
                </div>
            </body>
            </html>
            """, resetUrl, resetUrl);

        try {
            log.info("Sending password reset email to: {}", to);
            
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromEmail, "OntoCode Team");
            helper.setTo(to);
            helper.setSubject("OntoCode: Password Reset Request");
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Password reset email sent successfully to: {}", to);
        } catch (MessagingException e) {
            log.error("Failed to send password reset email to: {}", to, e);
            throw new RuntimeException("Failed to send password reset email", e);
        } catch (Exception e) {
            log.error("Failed to send password reset email to: {}", to, e);
            throw new RuntimeException("Failed to send password reset email", e);
        }
    }

    /**
     * Send password change notification email
     */
    public void sendPasswordChangeEmail(String to, String username) {
        String htmlContent = String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body {
                        font-family: Arial, sans-serif;
                        line-height: 1.6;
                        color: #333;
                    }
                    .container {
                        max-width: 600px;
                        margin: 0 auto;
                        padding: 20px;
                        background-color: #f9f9f9;
                    }
                    .content {
                        background-color: white;
                        padding: 30px;
                        border-radius: 8px;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                    }
                    .button {
                        display: inline-block;
                        background-color: #8B5CF6;
                        color: white !important;
                        padding: 12px 24px;
                        text-decoration: none;
                        border-radius: 6px;
                        margin: 20px 0;
                    }
                    .footer {
                        margin-top: 20px;
                        font-size: 12px;
                        color: #666;
                    }
                    .success {
                        background-color: #d4edda;
                        border-left: 4px solid #28a745;
                        padding: 15px;
                        margin: 20px 0;
                        border-radius: 4px;
                    }
                    .warning {
                        background-color: #fff3cd;
                        border-left: 4px solid #ffc107;
                        padding: 15px;
                        margin: 20px 0;
                        border-radius: 4px;
                    }
                    .icon {
                        font-size: 48px;
                        text-align: center;
                        margin: 20px 0;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="content">
                        <div class="icon">🔐</div>
                        <h1 style="color: #8B5CF6; text-align: center;">Password Changed Successfully</h1>
                        <div class="success">
                            <strong>✓ Your password has been changed</strong>
                            <p style="margin: 10px 0 0 0;">Your OntoCode account password was successfully changed.</p>
                        </div>
                        <p>Hello <strong>%s</strong>,</p>
                        <p>This is a confirmation that your password for your OntoCode account has been successfully changed.</p>
                        <p><strong>Time:</strong> %s</p>
                        <div class="warning">
                            <strong>⚠️ Security Notice:</strong>
                            <p style="margin: 10px 0 0 0;">If you did not make this change, your account may be compromised. Please contact our support team immediately at <a href="mailto:support@ontocode.com">support@ontocode.com</a>.</p>
                        </div>
                        <h3>What happens next?</h3>
                        <ul>
                            <li>You will be automatically logged out from all devices for security</li>
                            <li>You can log in again using your new password</li>
                            <li>Your active sessions have been terminated</li>
                        </ul>
                        <p>If you have any questions or concerns, please don't hesitate to reach out to our support team.</p>
                        <p class="footer">
                            Best regards,<br>
                            The OntoCode Team<br>
                            <a href="mailto:support@ontocode.com">support@ontocode.com</a>
                        </p>
                    </div>
                </div>
            </body>
            </html>
            """, 
            username,
            java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMMM dd, yyyy 'at' hh:mm a"))
        );

        try {
            log.info("Sending password change notification email to: {}", to);
            
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromEmail, "OntoCode Security");
            helper.setTo(to);
            helper.setSubject("OntoCode: Password Changed Successfully");
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Password change notification email sent successfully to: {}", to);
        } catch (MessagingException e) {
            log.error("Failed to send password change email to: {}", to, e);
            throw new RuntimeException("Failed to send password change email", e);
        } catch (Exception e) {
            log.error("Failed to send password change email to: {}", to, e);
            throw new RuntimeException("Failed to send password change email", e);
        }
    }
}