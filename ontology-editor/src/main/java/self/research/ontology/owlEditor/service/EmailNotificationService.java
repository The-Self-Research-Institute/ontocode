package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Service for sending email notifications for file sharing using Gmail SMTP
 */
@Slf4j
@Service
public class EmailNotificationService {
    
    private final JavaMailSender mailSender;
    
    @Value("${app.base-url:http://localhost:8083}")
    private String baseUrl;
    
    @Value("${spring.mail.username:}")
    private String fromEmail;
    
    @Value("${app.email.enabled:false}")
    private boolean emailEnabled;
    
    public EmailNotificationService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }
    
    /**
     * Send file share notification email
     * 
     * @param toEmail Recipient email address
     * @param fromUsername Username of person sharing the file
     * @param fileName Name of the shared file
     * @param permission Permission level ("view" or "edit")
     */
    public void sendShareNotification(String toEmail, String fromUsername, String fileName, String permission) {
        if (!emailEnabled || fromEmail == null || fromEmail.isEmpty()) {
            log.info("Email notifications disabled or not configured. Skipping email to {}", toEmail);
            return;
        }
        
        try {
            String subject = fromUsername + " shared a file with you";
            String accessLevel = permission.equals("edit") ? "edit" : "view";
            
            String htmlContent = buildShareEmailHtml(fromUsername, fileName, accessLevel);
            
            sendEmail(toEmail, subject, htmlContent);
            
            log.info("Share notification email sent to {} for file: {}", toEmail, fileName);
        } catch (Exception e) {
            log.error("Failed to send share notification email to {}", toEmail, e);
            // Don't throw exception - email failure shouldn't break sharing functionality
        }
    }
    
    private String buildShareEmailHtml(String fromUsername, String fileName, String accessLevel) {
        String accessDescription = accessLevel.equals("edit") 
            ? "You can view and edit this file." 
            : "You can view this file.";
            
        return String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body {
                        font-family: Arial, sans-serif;
                        line-height: 1.6;
                        color: #333;
                        margin: 0;
                        padding: 0;
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
                    .header {
                        border-bottom: 3px solid #8B5CF6;
                        padding-bottom: 15px;
                        margin-bottom: 20px;
                    }
                    h1 {
                        color: #8B5CF6;
                        margin: 0;
                        font-size: 24px;
                    }
                    .file-info {
                        background-color: #f4f4f4;
                        padding: 15px;
                        border-radius: 6px;
                        margin: 20px 0;
                    }
                    .file-name {
                        font-weight: bold;
                        color: #8B5CF6;
                        font-size: 16px;
                    }
                    .access-badge {
                        display: inline-block;
                        background-color: #10B981;
                        color: white;
                        padding: 4px 12px;
                        border-radius: 4px;
                        font-size: 12px;
                        font-weight: bold;
                        text-transform: uppercase;
                        margin-top: 8px;
                    }
                    .button {
                        display: inline-block;
                        background-color: #8B5CF6;
                        color: white !important;
                        padding: 12px 24px;
                        text-decoration: none;
                        border-radius: 6px;
                        margin: 20px 0;
                        font-weight: bold;
                    }
                    .button:hover {
                        background-color: #7C3AED;
                    }
                    .footer {
                        margin-top: 30px;
                        padding-top: 20px;
                        border-top: 1px solid #e5e5e5;
                        font-size: 12px;
                        color: #666;
                    }
                    .instructions {
                        background-color: #FEF3C7;
                        border-left: 4px solid #F59E0B;
                        padding: 12px;
                        margin: 15px 0;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="content">
                        <div class="header">
                            <h1>📁 File Shared With You</h1>
                        </div>
                        
                        <p>Hi there,</p>
                        
                        <p><strong>%s</strong> has shared a file with you on OntoCode.</p>
                        
                        <div class="file-info">
                            <div class="file-name">%s</div>
                            <div class="access-badge">%s ACCESS</div>
                        </div>
                        
                        <p>%s</p>
                        
                        <div class="instructions">
                            <strong>📝 To access this file:</strong>
                            <ol style="margin: 8px 0; padding-left: 20px;">
                                <li>Click the button below to login to OntoCode</li>
                                <li>Navigate to "Shared Files" section in your dashboard</li>
                                <li>Find and open the shared file</li>
                            </ol>
                        </div>
                        
                        <a href="%s" class="button">Login to View File</a>
                        
                        <div class="footer">
                            <p>
                                <strong>OntoCode</strong> - Ontology Editor Platform<br>
                                If you believe you received this email in error, please ignore it.
                            </p>
                        </div>
                    </div>
                </div>
            </body>
            </html>
            """,
            fromUsername,
            fileName,
            accessLevel.toUpperCase(),
            accessDescription,
            baseUrl
        );
    }
    
    private void sendEmail(String toEmail, String subject, String htmlContent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlContent, true); // true = HTML
            
            mailSender.send(message);
            
            log.info("Email sent successfully to: {}", toEmail);
        } catch (MessagingException e) {
            log.error("Failed to send email", e);
            throw new RuntimeException("Failed to send email", e);
        }
    }
}
