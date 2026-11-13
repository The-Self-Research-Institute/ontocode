package self.research.ontology.owlEditor.document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProjectPreferences {

    private String id;

    private String userEmail;
    private String projectId;

    private String syncMode;

    private Instant updatedAt;

    public UserProjectPreferences(String userEmail, String projectId, String syncMode) {
        this.id = UUID.randomUUID().toString();
        this.userEmail = userEmail;
        this.projectId = projectId;
        this.syncMode = syncMode;
        this.updatedAt = Instant.now();
    }
}
