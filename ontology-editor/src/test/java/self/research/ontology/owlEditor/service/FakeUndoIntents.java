package self.research.ontology.owlEditor.service;

import org.springframework.dao.DuplicateKeyException;
import self.research.ontology.owlEditor.document.UndoIntentDocument;
import self.research.ontology.owlEditor.repository.UndoIntentRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class FakeUndoIntents {

    final List<UndoIntentDocument> stored = new ArrayList<>();
    final UndoIntentRepository repository = mock(UndoIntentRepository.class);
    final UndoIntentService service = new UndoIntentService(repository);

    @SuppressWarnings("unchecked")
    FakeUndoIntents() {
        when(repository.save(any())).thenAnswer(invocation -> {
            UndoIntentDocument intent = invocation.getArgument(0);
            if (intent.getId() == null) {
                boolean taken = intent.getOpId() != null && stored.stream().anyMatch(other ->
                        intent.getProjectId().equals(other.getProjectId()) && intent.getOpId().equals(other.getOpId()));
                if (taken) {
                    throw new DuplicateKeyException("duplicate operation id");
                }
                intent.setId(UUID.randomUUID().toString());
                stored.add(intent);
            }
            return intent;
        });
        when(repository.findByProjectIdAndOpId(anyString(), anyString())).thenAnswer(invocation -> stored.stream()
                .filter(intent -> invocation.getArgument(0).equals(intent.getProjectId())
                        && invocation.getArgument(1).equals(intent.getOpId()))
                .findFirst());
        when(repository.findByProjectIdAndStatusIn(anyString(), any())).thenAnswer(invocation -> stored.stream()
                .filter(intent -> invocation.getArgument(0).equals(intent.getProjectId())
                        && ((java.util.Collection<UndoIntentDocument.Status>) invocation.getArgument(1)).contains(intent.getStatus()))
                .toList());
        when(repository.findByStatusIn(any())).thenAnswer(invocation -> stored.stream()
                .filter(intent -> ((java.util.Collection<UndoIntentDocument.Status>) invocation.getArgument(0)).contains(intent.getStatus()))
                .toList());
    }

    Optional<UndoIntentDocument> only() {
        return stored.size() == 1 ? Optional.of(stored.get(0)) : Optional.empty();
    }

    UndoIntentDocument seed(String projectId, String opId, String direction, UndoIntentDocument.Status status,
                            UndoIntentDocument.Stage stage, List<String> changeIds, String auditId, String undoAuditId) {
        UndoIntentDocument intent = UndoIntentDocument.builder().projectId(projectId).opId(opId).direction(direction)
                .actor("u@x.com").status(status).stage(stage).changeIds(changeIds).auditId(auditId).undoAuditId(undoAuditId)
                .build();
        repository.save(intent);
        return intent;
    }
}
