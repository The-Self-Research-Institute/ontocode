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

    @Value("${stripe.trial-period-days:14}")
    private Long trialPeriodDays;

    public EmailService() {
        log.info("✓ SMTP Email service initialized");
    }

    /**
     * Send invitation email to the invitee
     */
    public void sendInvitationEmail(self.research.ontology.auth.model.Invitation invitation) {
        // Generate both VS Code extension and webview links using configured base URL
        String webviewLink = baseUrl + "/?token=" + invitation.getInvitationToken();
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
                    .token-box {
                        background-color: #fef3c7;
                        padding: 15px;
                        border-radius: 6px;
                        margin: 15px 0;
                        border-left: 4px solid #f59e0b;
                    }
                    .token-value {
                        font-family: 'Courier New', monospace;
                        background: white;
                        padding: 10px;
                        border-radius: 4px;
                        word-break: break-all;
                        font-size: 13px;
                        color: #1f2937;
                        border: 1px solid #d1d5db;
                        margin: 8px 0;
                    }
                    .steps {
                        background-color: #eff6ff;
                        padding: 15px;
                        border-radius: 6px;
                        margin: 15px 0;
                        border-left: 4px solid #3b82f6;
                    }
                    .step-item {
                        margin: 8px 0;
                        padding-left: 10px;
                    }
                    .kbd {
                        background-color: #1f2937;
                        color: white;
                        padding: 2px 6px;
                        border-radius: 3px;
                        font-family: monospace;
                        font-size: 12px;
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
                        
                        <div class="token-box">
                            <strong>🔑 Your Invitation Token:</strong>
                            <div class="token-value">%s</div>
                            <p style="margin: 8px 0 0 0; font-size: 12px; color: #92400e;">Copy this token to accept the invitation via VS Code Command Palette</p>
                        </div>
                        
                        <div class="info-box">
                            <strong>🎯 Choose how to accept:</strong>
                            <p style="margin: 10px 0 5px 0; font-size: 14px;">For the best experience with VS Code integration:</p>
                        </div>
                        
                        <div class="options">
                            <a href="%s" class="button" style="color: white; text-decoration: none;">🚀 Open in VS Code</a>
                            <br>
                            <a href="%s" class="button button-secondary" style="color: white; text-decoration: none;">🌐 Open in Webview</a>
                        </div>
                        
                        <div class="steps">
                            <strong>📋 Alternative: Manual Setup via Command Palette</strong>
                            <p style="margin: 10px 0 5px 0; font-size: 13px;">If the buttons don't work, follow these steps in VS Code:</p>
                            <ol style="margin: 8px 0; padding-left: 20px; font-size: 13px;">
                                <li class="step-item">Open VS Code</li>
                                <li class="step-item">Press <span class="kbd">Ctrl+Shift+P</span> (Windows/Linux) or <span class="kbd">Cmd+Shift+P</span> (Mac)</li>
                                <li class="step-item">Type: <strong>OntoCode: Test Invitation Flow</strong></li>
                                <li class="step-item">Paste your invitation token (shown above)</li>
                                <li class="step-item">Press Enter to accept the invitation</li>
                            </ol>
                        </div>
                        
                        <div style="background-color: #f9fafb; padding: 15px; border-radius: 6px; margin: 20px 0;">
                            <p style="margin: 0 0 10px 0; font-size: 13px; color: #6b7280;"><strong>Direct Links:</strong></p>
                            <p style="margin: 5px 0 2px 0; font-size: 11px; color: #6b7280;">Webview Link:</p>
                            <p style="margin: 0 0 10px 0; font-size: 12px; word-break: break-all; color: #4b5563; font-family: monospace; background: white; padding: 8px; border-radius: 4px;">%s</p>
                            <p style="margin: 5px 0 2px 0; font-size: 11px; color: #6b7280;">VS Code Deep Link:</p>
                            <p style="margin: 0; font-size: 12px; word-break: break-all; color: #4b5563; font-family: monospace; background: white; padding: 8px; border-radius: 4px;">%s</p>
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
            invitation.getInvitationToken(),
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
        String verificationUrl = baseUrl + "/verify-email?token=" + token;
        
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

    // ─────────────────────────────────────────────────────────────────────────
    // Billing / Subscription emails
    // ─────────────────────────────────────────────────────────────────────────

    public void sendTrialStartedEmail(String to, String username, String planName, String trialEndDate, String billingPortalUrl) {
        String plan = toDisplayName(planName);
        String trialLabel = trialPeriodDays + "-day";
        String html = billingHtml(
            "🎉 Your " + trialLabel + " free trial has started!",
            String.format("""
                <p>Hi <strong>%s</strong>,</p>
                <p>Welcome to OntoCode <span class="badge">%s</span>. Your free trial is now active — your card has been saved securely but <strong>will not be charged</strong> until the trial ends.</p>
                <div class="info-box">
                  <strong>Trial summary</strong>
                  <table style="margin-top:10px;width:100%%;border-collapse:collapse;">
                    <tr><td style="padding:4px 0;color:#6b7280;">Plan</td><td style="padding:4px 0;font-weight:600;">%s</td></tr>
                    <tr><td style="padding:4px 0;color:#6b7280;">Trial ends</td><td style="padding:4px 0;font-weight:600;">%s</td></tr>
                    <tr><td style="padding:4px 0;color:#6b7280;">First charge</td><td style="padding:4px 0;font-weight:600;">After %s (only if not cancelled)</td></tr>
                  </table>
                </div>
                <p>Explore every feature — live collaboration, advanced SPARQL, full ontology tooling. Cancel before <strong>%s</strong> and you'll never be billed.</p>
                <a href="%s" class="button">Manage your subscription</a>
                """,
                username, plan, plan, trialEndDate, trialEndDate, trialEndDate, billingPortalUrl),
            "Questions? Contact <a href='mailto:support@ontocode.com'>support@ontocode.com</a>"
        );
        sendHtml(to, "Your OntoCode " + plan + " trial has started — " + trialLabel + " free", html);
    }

    public void sendTrialEndingReminderEmail(String to, String username, String planName,
                                             long daysLeft, String trialEndDate, String billingPortalUrl) {
        String plan = toDisplayName(planName);
        String daysLabel = daysLeft == 1 ? "1 day" : daysLeft + " days";
        String html = billingHtml(
            "⏰ Your free trial ends in " + daysLabel,
            String.format("""
                <p>Hi <strong>%s</strong>,</p>
                <p>Your OntoCode <span class="badge">%s</span> trial ends on <strong>%s</strong>. After that your saved payment method will be charged and your subscription will continue uninterrupted.</p>
                <div class="warning-box">
                  <strong>What happens on %s:</strong>
                  <ul style="margin:8px 0 0 0;padding-left:20px;">
                    <li>Your trial ends</li>
                    <li>Your payment method is charged for the first billing period</li>
                    <li>Full %s features remain available</li>
                  </ul>
                </div>
                <p>Not ready to continue? You can cancel before <strong>%s</strong> — no charge, no hassle.</p>
                <a href="%s" class="button">Review or cancel your subscription</a>
                """,
                username, plan, trialEndDate, trialEndDate, plan, trialEndDate, billingPortalUrl),
            "You're receiving this because you have an active trial on OntoCode."
        );
        sendHtml(to, "Your OntoCode trial ends in " + daysLabel + " — " + trialEndDate, html);
    }

    public void sendPaymentSucceededEmail(String to, String username, String planName,
                                          String amountFormatted, String nextBillingDate, String invoiceUrl) {
        String plan = toDisplayName(planName);
        String html = billingHtml(
            "✅ Payment received — thank you!",
            String.format("""
                <p>Hi <strong>%s</strong>,</p>
                <p>We successfully processed your payment for OntoCode <span class="badge">%s</span>.</p>
                <div class="info-box">
                  <strong>Payment details</strong>
                  <table style="margin-top:10px;width:100%%;border-collapse:collapse;">
                    <tr><td style="padding:4px 0;color:#6b7280;">Amount charged</td><td style="padding:4px 0;font-weight:600;">%s</td></tr>
                    <tr><td style="padding:4px 0;color:#6b7280;">Plan</td><td style="padding:4px 0;font-weight:600;">%s</td></tr>
                    <tr><td style="padding:4px 0;color:#6b7280;">Next billing date</td><td style="padding:4px 0;font-weight:600;">%s</td></tr>
                  </table>
                </div>
                %s
                """,
                username, plan, amountFormatted, plan, nextBillingDate,
                invoiceUrl != null ? "<a href=\"" + invoiceUrl + "\" class=\"button-outline\">View invoice</a>" : ""),
            "Manage your subscription at any time from the OntoCode billing portal."
        );
        sendHtml(to, "Payment confirmed — OntoCode " + plan, html);
    }

    public void sendPaymentFailedEmail(String to, String username, String planName,
                                       String amountFormatted, String updatePaymentUrl) {
        String plan = toDisplayName(planName);
        String html = billingHtml(
            "⚠️ Action required: payment failed",
            String.format("""
                <p>Hi <strong>%s</strong>,</p>
                <p>We were unable to process your payment of <strong>%s</strong> for your OntoCode <span class="badge">%s</span> subscription.</p>
                <div class="warning-box">
                  <strong>What you need to do:</strong>
                  <p style="margin:8px 0 0 0;">Update your payment method as soon as possible to avoid losing access to your workspace. Stripe will automatically retry the charge — updating your card before the next retry ensures no interruption.</p>
                </div>
                <a href="%s" class="button">Update payment method</a>
                <p>If you believe this is an error or need help, please contact <a href="mailto:support@ontocode.com">support@ontocode.com</a> right away.</p>
                """,
                username, amountFormatted, plan, updatePaymentUrl),
            "Your access remains active during the retry period. Please update your payment method promptly."
        );
        sendHtml(to, "Action required: OntoCode payment failed — update your card", html);
    }

    public void sendSubscriptionCancelledEmail(String to, String username, String planName, String cancelledDate) {
        String plan = toDisplayName(planName);
        String html = billingHtml(
            "Your subscription has been cancelled",
            String.format("""
                <p>Hi <strong>%s</strong>,</p>
                <p>Your OntoCode <span class="badge">%s</span> subscription has been cancelled. Workspace access for paid plans is blocked until the plan is renewed.</p>
                <div class="info-box">
                  <strong>Access details</strong>
                  <table style="margin-top:10px;width:100%%;border-collapse:collapse;">
                    <tr><td style="padding:4px 0;color:#6b7280;">Plan cancelled</td><td style="padding:4px 0;font-weight:600;">%s</td></tr>
                    <tr><td style="padding:4px 0;color:#6b7280;">Cancelled on</td><td style="padding:4px 0;font-weight:600;">%s</td></tr>
                    <tr><td style="padding:4px 0;color:#6b7280;">Workspace access</td><td style="padding:4px 0;font-weight:600;">Blocked until renewal</td></tr>
                  </table>
                </div>
                <p>Your data is retained. Renew your existing plan to restore workspace access.</p>
                <a href="%s" class="button">Renew subscription</a>
                <p>If you cancelled by mistake or have questions, please reach out to <a href="mailto:support@ontocode.com">support@ontocode.com</a>.</p>
                """,
                username, plan, plan, cancelledDate, baseUrl),
            "Your workspace data is retained, but paid workspace access is blocked until renewal."
        );
        sendHtml(to, "Your OntoCode " + plan + " subscription has been cancelled", html);
    }

    private void sendSubscriptionCancelledEmailLegacy(String to, String username, String planName, String accessEndDate) {
        String plan = toDisplayName(planName);
        String html = billingHtml(
            "Your subscription has been cancelled",
            String.format("""
                <p>Hi <strong>%s</strong>,</p>
                <p>Your OntoCode <span class="badge">%s</span> subscription has been cancelled. We're sorry to see you go.</p>
                <div class="info-box">
                  <strong>Access details</strong>
                  <table style="margin-top:10px;width:100%%;border-collapse:collapse;">
                    <tr><td style="padding:4px 0;color:#6b7280;">Plan cancelled</td><td style="padding:4px 0;font-weight:600;">%s</td></tr>
                    <tr><td style="padding:4px 0;color:#6b7280;">Access until</td><td style="padding:4px 0;font-weight:600;">%s</td></tr>
                    <tr><td style="padding:4px 0;color:#6b7280;">After that</td><td style="padding:4px 0;font-weight:600;">Workspace moves to Free plan</td></tr>
                  </table>
                </div>
                <p>Your data is safe — everything stays in your workspace on the Free plan. If you change your mind, you can resubscribe at any time.</p>
                <a href="%s" class="button">Resubscribe</a>
                <p>If you cancelled by mistake or have questions, please reach out to <a href="mailto:support@ontocode.com">support@ontocode.com</a>.</p>
                """,
                username, plan, plan, accessEndDate, baseUrl),
            "Your workspace and all data are retained on the Free plan after cancellation."
        );
        sendHtml(to, "Your OntoCode " + plan + " subscription has been cancelled", html);
    }

    public void sendRenewalReminderEmail(String to, String username, String planName,
                                        int daysBefore, String renewalDate, String amountFormatted, String billingPortalUrl) {
        String plan = toDisplayName(planName);
        String dayLabel = daysBefore == 1 ? "1 day" : daysBefore + " days";
        String html = billingHtml(
            "Your subscription renews in " + dayLabel,
            String.format("""
                <p>Hi <strong>%s</strong>,</p>
                <p>Just a heads-up: your OntoCode <span class="badge">%s</span> subscription will automatically renew on <strong>%s</strong>.</p>
                <div class="info-box">
                  <strong>Renewal details</strong>
                  <table style="margin-top:10px;width:100%%;border-collapse:collapse;">
                    <tr><td style="padding:4px 0;color:#6b7280;">Plan</td><td style="padding:4px 0;font-weight:600;">%s</td></tr>
                    <tr><td style="padding:4px 0;color:#6b7280;">Renewal date</td><td style="padding:4px 0;font-weight:600;">%s</td></tr>
                    <tr><td style="padding:4px 0;color:#6b7280;">Amount</td><td style="padding:4px 0;font-weight:600;">%s</td></tr>
                  </table>
                </div>
                <p>No action is needed if you want to continue. To cancel or update your payment method before renewal, visit billing settings.</p>
                <a href="%s" class="button">Manage billing</a>
                """,
                username, plan, renewalDate, plan, renewalDate, amountFormatted, billingPortalUrl),
            "You're receiving this renewal reminder because you have an active OntoCode subscription."
        );
        sendHtml(to, "OntoCode " + plan + " renews in " + dayLabel + " on " + renewalDate + " - " + amountFormatted, html);
    }

    private void sendRenewalReminderEmailLegacy(String to, String username, String planName,
                                        int daysBefore, String renewalDate, String amountFormatted, String billingPortalUrl) {
        String plan = toDisplayName(planName);
        String dayLabel = daysBefore == 1 ? "1 day" : daysBefore + " days";
        String html = billingHtml(
            "📅 Your subscription renews in 3 days",
            String.format("""
                <p>Hi <strong>%s</strong>,</p>
                <p>Just a heads-up — your OntoCode <span class="badge">%s</span> subscription will automatically renew on <strong>%s</strong>.</p>
                <div class="info-box">
                  <strong>Renewal details</strong>
                  <table style="margin-top:10px;width:100%%;border-collapse:collapse;">
                    <tr><td style="padding:4px 0;color:#6b7280;">Plan</td><td style="padding:4px 0;font-weight:600;">%s</td></tr>
                    <tr><td style="padding:4px 0;color:#6b7280;">Renewal date</td><td style="padding:4px 0;font-weight:600;">%s</td></tr>
                    <tr><td style="padding:4px 0;color:#6b7280;">Amount</td><td style="padding:4px 0;font-weight:600;">%s</td></tr>
                  </table>
                </div>
                <p>No action needed if you'd like to continue. To cancel or update your payment method before renewal, visit billing settings.</p>
                <a href="%s" class="button">Manage billing</a>
                """,
                username, plan, renewalDate, plan, renewalDate, amountFormatted, billingPortalUrl),
            "You're receiving this renewal reminder because you have an active OntoCode subscription."
        );
        sendHtml(to, "OntoCode " + plan + " renews on " + renewalDate + " — " + amountFormatted, html);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String toDisplayName(String planName) {
        if (planName == null) return "Professional";
        return switch (planName.toUpperCase()) {
            case "ENTERPRISE" -> "Enterprise";
            default -> "Professional";
        };
    }

    private String billingHtml(String heading, String body, String footerNote) {
        return """
            <!DOCTYPE html><html><head><style>
            body{font-family:Arial,sans-serif;line-height:1.6;color:#333;margin:0;}
            .container{max-width:600px;margin:0 auto;padding:20px;background:#f9f9f9;}
            .content{background:white;padding:30px;border-radius:8px;box-shadow:0 2px 4px rgba(0,0,0,.1);}
            .button{display:inline-block;background:#8B5CF6;color:white!important;padding:12px 28px;
                    text-decoration:none;border-radius:6px;margin:20px 0;font-weight:600;}
            .button-outline{display:inline-block;border:2px solid #8B5CF6;color:#8B5CF6!important;
                            padding:10px 24px;text-decoration:none;border-radius:6px;margin:10px 0;font-weight:600;}
            .badge{display:inline-block;background:#ede9fe;color:#6d28d9;padding:3px 10px;
                   border-radius:12px;font-size:13px;font-weight:600;}
            .info-box{background:#f5f3ff;border-left:4px solid #8B5CF6;padding:15px;border-radius:6px;margin:20px 0;}
            .warning-box{background:#fffbeb;border-left:4px solid #f59e0b;padding:15px;border-radius:6px;margin:20px 0;}
            .footer{margin-top:24px;font-size:12px;color:#9ca3af;border-top:1px solid #f3f4f6;padding-top:16px;}
            </style></head><body>
            <div class="container"><div class="content">
            <div style="margin-bottom:24px;">
              <span style="font-size:13px;font-weight:700;color:#8B5CF6;letter-spacing:1px;text-transform:uppercase;">OntoCode</span>
            </div>
            <h1 style="color:#111827;font-size:22px;margin:0 0 20px 0;">""" + heading + """
            </h1>
            """ + body + """
            <div class="footer">""" + footerNote + """
            <br>OntoCode · <a href="mailto:support@ontocode.com">support@ontocode.com</a>
            </div>
            </div></div></body></html>
            """;
    }

    private void sendHtml(String to, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, "OntoCode");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("Sent '{}' to {}", subject, to);
        } catch (MessagingException e) {
            log.error("Failed to send '{}' to {}: {}", subject, to, e.getMessage());
            throw new RuntimeException("Failed to send email: " + subject, e);
        } catch (Exception e) {
            log.error("Failed to send '{}' to {}: {}", subject, to, e.getMessage());
            throw new RuntimeException("Failed to send email: " + subject, e);
        }
    }

    /**
     * Notify a user that they have been granted access to a project.
     */
    public void sendProjectAccessEmail(String toEmail, String toUsername, String projectName,
                                       String role, String grantedByUsername) {
        String projectUrl = baseUrl + "/projects";
        String htmlContent = String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; background-color: #f9f9f9; }
                    .content { background-color: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                    .button { display: inline-block; background-color: #8B5CF6; color: white !important; padding: 12px 24px; text-decoration: none; border-radius: 6px; margin: 20px 0; font-weight: 600; }
                    .badge { display: inline-block; background-color: #e0e7ff; color: #4338ca; padding: 4px 12px; border-radius: 12px; font-size: 12px; font-weight: 600; }
                    .info-box { background-color: #f3f4f6; padding: 15px; border-radius: 6px; margin: 15px 0; border-left: 4px solid #8B5CF6; }
                    .footer { margin-top: 20px; font-size: 12px; color: #666; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="content">
                        <h1 style="color: #8B5CF6;">You have been added to a project</h1>
                        <p>Hi <strong>%s</strong>,</p>
                        <p><strong>%s</strong> has granted you access to the following project:</p>
                        <div class="info-box">
                            <strong>Project:</strong> %s<br/>
                            <strong>Your role:</strong> <span class="badge">%s</span>
                        </div>
                        <p>You can now open OntoCode and access this project from your project library.</p>
                        <a href="%s" class="button">Open Project Library</a>
                        <p class="footer">
                            If you believe this is a mistake, please contact the workspace owner.
                        </p>
                    </div>
                </div>
            </body>
            </html>
            """, toUsername, grantedByUsername, projectName, role, projectUrl);

        try {
            log.info("Sending project access email to: {}", toEmail);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, "OntoCode Team");
            helper.setTo(toEmail);
            helper.setSubject("OntoCode: You have been added to \"" + projectName + "\"");
            helper.setText(htmlContent, true);
            mailSender.send(message);
            log.info("Project access email sent to: {}", toEmail);
        } catch (Exception e) {
            log.warn("Failed to send project access email to {}: {}", toEmail, e.getMessage());
        }
    }
}
