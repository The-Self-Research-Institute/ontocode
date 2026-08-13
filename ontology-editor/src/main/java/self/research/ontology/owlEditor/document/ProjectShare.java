package self.research.ontology.owlEditor.document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "project_shares")
public class ProjectShare {

    @Id
    private String id;

    private String projectId;
    private String ownerEmail;
    private List<String> sharedWithEmails = new ArrayList<>();
    private String shareLink;
    private String permission;
    private Instant createdAt;
    private Instant updatedAt;

    public ProjectShare(String projectId, String ownerEmail, String permission) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.ownerEmail = ownerEmail;
        this.permission = permission;
        this.shareLink = generateShareLink();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.sharedWithEmails = new ArrayList<>();
    }

    private String generateShareLink() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public void addSharedEmail(String email) {
        if (!sharedWithEmails.contains(email)) {
            sharedWithEmails.add(email);
            updatedAt = Instant.now();
        }
    }

    public void removeSharedEmail(String email) {
        sharedWithEmails.remove(email);
        updatedAt = Instant.now();
    }
}
