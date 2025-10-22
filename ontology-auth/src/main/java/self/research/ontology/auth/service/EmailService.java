// src/main/java/self/research/ontology/auth/service/EmailService.java
package self.research.ontology.auth.service;

import io.mailtrap.client.MailtrapClient;
import io.mailtrap.config.MailtrapConfig;
import io.mailtrap.factory.MailtrapClientFactory;
import io.mailtrap.model.request.emails.Address;
import io.mailtrap.model.request.emails.MailtrapMail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.List;

@Service
public class EmailService {

    private final MailtrapClient mailtrapClient;

    @Value("${mailtrap.api.token}")
    private String mailtrapToken;

    public EmailService(@Value("${mailtrap.api.token}") String mailtrapToken) {
        this.mailtrapToken = mailtrapToken;
        final MailtrapConfig config = new MailtrapConfig.Builder()
                .token(mailtrapToken)
                .build();
        this.mailtrapClient = MailtrapClientFactory.createMailtrapClient(config);
    }

    public void sendVerificationEmail(String to, String token) {
        final MailtrapMail mail = MailtrapMail.builder()
                .from(new Address("hi@demomailtrap.co", "OntoCode")) // Use your email
                .to(List.of(new Address(to)))
                .subject("OntoCode: Please Verify Your Email Address")
                .text("To verify your account, please click the link below:\n"
                        + "http://localhost:8082/api/auth/verify?token=" + token)
                .category("Account Verification")
                .build();

        try {
            System.out.println("Sending email via Mailtrap...");
            System.out.println(token);
            mailtrapClient.send(mail);
            System.out.println("Email sent successfully!");
        } catch (Exception e) {
            System.err.println("Failed to send email via Mailtrap: " + e.getMessage());
            e.printStackTrace();
        }
    }
}