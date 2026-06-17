package self.research.ontology.auth.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import self.research.ontology.auth.model.DesktopDownloadEvent;

public interface DesktopDownloadEventRepository extends MongoRepository<DesktopDownloadEvent, String> {
    long countByPlatformAndEventType(String platform, String eventType);
}
