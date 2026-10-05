package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.AssistantAuditEntryDocument;
import self.research.ontology.owlEditor.repository.AssistantAuditRepository;

import java.time.Instant;

@Slf4j
@Service
public class AssistantAuditService {

    private final AssistantAuditRepository repository;

    public AssistantAuditService(AssistantAuditRepository repository) {
        this.repository = repository;
    }

    public record AssistantAuditEvent(String actor, String projectId, String sessionId, String groupId,
                                      String operation, Long sourceRevision, String provider, String model,
                                      String outcome, String errorCode, String detail) {}

    public void record(AssistantAuditEvent event) {
        log.info("[AssistantAudit] op={} outcome={} errorCode={} project={} session={} group={} revision={} provider={} model={}",
                event.operation(), event.outcome(), event.errorCode(), event.projectId(), event.sessionId(),
                event.groupId(), event.sourceRevision(), event.provider(), event.model());
        try {
            repository.save(AssistantAuditEntryDocument.builder()
                    .actor(event.actor())
                    .projectId(event.projectId())
                    .sessionId(event.sessionId())
                    .groupId(event.groupId())
                    .operation(event.operation())
                    .sourceRevision(event.sourceRevision())
                    .provider(event.provider())
                    .model(event.model())
                    .outcome(event.outcome())
                    .errorCode(event.errorCode())
                    .detail(event.detail())
                    .createdAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.warn("[AssistantAudit] Could not persist audit entry for op {}: {}", event.operation(), e.getMessage());
        }
    }
}
