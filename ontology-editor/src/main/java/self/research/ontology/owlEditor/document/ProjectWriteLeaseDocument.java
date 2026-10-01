package self.research.ontology.owlEditor.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "project_write_leases")
public class ProjectWriteLeaseDocument {

    public static final String FIELD_ID = "_id";
    public static final String FIELD_OWNER_TOKEN = "ownerToken";
    public static final String FIELD_HOLDER = "holder";
    public static final String FIELD_ACQUIRED_AT = "acquiredAt";
    public static final String FIELD_EXPIRES_AT = "expiresAt";

    @Id
    private String projectId;

    private String ownerToken;

    private String holder;

    private Instant acquiredAt;

    private Instant expiresAt;
}
