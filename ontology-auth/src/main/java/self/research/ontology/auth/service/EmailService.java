package self.research.ontology.auth.service;

import io.mailtrap.client.MailtrapClient;
import io.mailtrap.config.MailtrapConfig;
import io.mailtrap.factory.MailtrapClientFactory;
import io.mailtrap.model.request.emails.Address;
import io.mailtrap.model.request.emails.MailtrapMail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    
    private final MailtrapClient mailtrapClient;

    @Value("${mailtrap.api.token}")
    private String mailtrapToken;

    @Value("${app.base-url:http://localhost:8082}")
    private String baseUrl;

    @Value("${app.email.from:noreply@ontocode.com}")
    private String fromEmail;

    public EmailService(@Value("${mailtrap.api.token}") String mailtrapToken) {
        this.mailtrapToken = mailtrapToken;
        final MailtrapConfig config = new MailtrapConfig.Builder()
                .token(mailtrapToken)
                .build();
        this.mailtrapClient = MailtrapClientFactory.createMailtrapClient(config);
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

        final MailtrapMail mail = MailtrapMail.builder()
                .from(new Address(fromEmail, "OntoCode"))
                .to(List.of(new Address(to)))
                .subject("OntoCode: Please Verify Your Email Address")
                .html(htmlContent)
                .category("Account Verification")
                .build();

        try {
            log.info("Sending verification email to: {}", to);
            mailtrapClient.send(mail);
            log.info("Verification email sent successfully to: {}", to);
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

        final MailtrapMail mail = MailtrapMail.builder()
                .from(new Address(fromEmail, "OntoCode"))
                .to(List.of(new Address(to)))
                .subject("OntoCode: Password Reset Request")
                .html(htmlContent)
                .category("Password Reset")
                .build();

        try {
            log.info("Sending password reset email to: {}", to);
            mailtrapClient.send(mail);
            log.info("Password reset email sent successfully to: {}", to);
        } catch (Exception e) {
            log.error("Failed to send password reset email to: {}", to, e);
            throw new RuntimeException("Failed to send password reset email", e);
        }
    }
}