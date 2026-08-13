package self.research.ontology.owlEditor.model.collaboration;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ShareNotification {
    private String projectId;
    private String fileName;
    private String sharedByUsername;
    private String sharedByEmail;
    private String sharedWithEmail;
    private String permission;
    private String message;
    private Long timestamp;
}
