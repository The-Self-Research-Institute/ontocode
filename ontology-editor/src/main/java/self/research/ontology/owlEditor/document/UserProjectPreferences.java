package self.research.ontology.owlEditor.document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "user_project_preferences")
@CompoundIndex(name = "user_project_idx", def = "{'userEmail': 1, 'projectId': 1}", unique = true)
public class UserProjectPreferences {

    @Id
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
