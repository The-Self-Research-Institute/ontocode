package self.research.ontology.auth.repository;

import java.time.LocalDateTime;
import java.util.Map;

public interface AuditEventRepository {

    void insert(String username, String eventType, String ipAddress, LocalDateTime timestamp,
                Map<String, String> metadata);
}
