package self.research.ontology.auth.repository;

import self.research.ontology.auth.model.EmailLog;

import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailLogRepository {

    boolean reserve(EmailLog log);

    boolean existsWithStatus(String key, String... statuses);

    void markSent(String key, LocalDateTime sentAt);

    void markFailed(String key, String errorMessage, LocalDateTime failedAt);

    Optional<EmailLog> findByKey(String key);
}
