package self.research.ontology.auth.repository;

import self.research.ontology.auth.model.DesktopDownloadEvent;

public interface DesktopDownloadEventRepository {

    DesktopDownloadEvent save(DesktopDownloadEvent event);

    long countByPlatformAndEventType(String platform, String eventType);
}
