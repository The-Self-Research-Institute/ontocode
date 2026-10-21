package self.research.ontology.owlEditor.service;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DatatypeDefinitionEntity;
import self.research.ontology.owlEditor.repository.DatatypeDefinitionRepository;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class DatatypeDefinitionDraftService implements DraftDocumentSupport {

    public record Change(DatatypeDefinitionEntity before, DatatypeDefinitionEntity after) {
    }

    private final DatatypeDefinitionRepository repository;
    private final OntologyMutationService mutationService;

    public DatatypeDefinitionDraftService(DatatypeDefinitionRepository repository,
                                          @Lazy OntologyMutationService mutationService) {
        this.repository = repository;
        this.mutationService = mutationService;
    }

    public List<DatatypeDefinitionEntity> list(String projectId, String datatypeIri, String userId) {
        List<DatatypeDefinitionEntity> shared =
                repository.findByProjectIdAndDatatypeIriAndDraftNotOrderByCreatedAtDesc(projectId, datatypeIri, true);
        List<DatatypeDefinitionEntity> mine =
                repository.findByProjectIdAndDatatypeIriAndOwnerUserIdAndDraftTrue(projectId, datatypeIri, userId);
        return DatatypeDefinitionDrafts.visible(shared, mine);
    }

    public Optional<DatatypeDefinitionEntity> findVisible(String projectId, String userId, String id) {
        return repository.findByIdAndProjectId(id, projectId)
                .filter(row -> !row.isDraft() || row.isOwnedBy(userId))
                .filter(row -> !row.isDeleted());
    }

    public Change create(String projectId, String userId, String datatypeIri, String expression, String definitionType) {
        mutationService.applyDraft(projectId, userId, List.of(addOp(datatypeIri, expression)));
        DatatypeDefinitionEntity row = newRow(projectId, userId, datatypeIri, expression, definitionType);
        return new Change(null, repository.save(row));
    }

    public Optional<Change> update(String projectId, String userId, String id, String expression, String definitionType) {
        return findVisible(projectId, userId, id).map(row -> {
            String nextExpression = isBlank(expression) ? row.getExpression() : expression;
            String nextType = isBlank(definitionType) ? row.getDefinitionType() : definitionType;
            mutationService.applyDraft(projectId, userId, List.of(deleteOp(row.getDatatypeIri())));
            mutationService.applyDraft(projectId, userId, List.of(addOp(row.getDatatypeIri(), nextExpression)));
            if (row.isOwnedBy(userId)) {
                DatatypeDefinitionEntity before = row.copy();
                row.setExpression(nextExpression);
                row.setDefinitionType(nextType);
                row.setUpdatedAt(new Date());
                return new Change(before, repository.save(row));
            }
            DatatypeDefinitionEntity override = newRow(projectId, userId, row.getDatatypeIri(), nextExpression, nextType);
            override.setReplacesId(row.getId());
            override.setCreatedAt(row.getCreatedAt());
            return new Change(null, repository.save(override));
        });
    }

    public Optional<Change> delete(String projectId, String userId, String id) {
        return findVisible(projectId, userId, id).map(row -> {
            mutationService.applyDraft(projectId, userId, List.of(deleteOp(row.getDatatypeIri())));
            if (row.isOwnedBy(userId) && row.getReplacesId() == null) {
                repository.delete(row);
                return new Change(row.copy(), null);
            }
            if (row.isOwnedBy(userId)) {
                DatatypeDefinitionEntity before = row.copy();
                row.setDeleted(true);
                row.setUpdatedAt(new Date());
                return new Change(before, repository.save(row));
            }
            DatatypeDefinitionEntity tombstone = newRow(projectId, userId, row.getDatatypeIri(), row.getExpression(),
                    row.getDefinitionType());
            tombstone.setReplacesId(row.getId());
            tombstone.setDeleted(true);
            return new Change(null, repository.save(tombstone));
        });
    }

    @Override
    public void promote(String projectId, String userId) {
        for (DatatypeDefinitionEntity row : repository.findByProjectIdAndOwnerUserIdAndDraftTrue(projectId, userId)) {
            Optional<DatatypeDefinitionEntity> shared = row.getReplacesId() == null
                    ? Optional.empty() : repository.findByIdAndProjectId(row.getReplacesId(), projectId);
            if (row.isDeleted()) {
                shared.ifPresent(repository::delete);
                repository.delete(row);
            } else if (shared.isPresent()) {
                DatatypeDefinitionEntity target = shared.get();
                target.setExpression(row.getExpression());
                target.setDefinitionType(row.getDefinitionType());
                target.setUpdatedAt(new Date());
                repository.save(target);
                repository.delete(row);
            } else {
                row.setDraft(false);
                row.setOwnerUserId(null);
                row.setReplacesId(null);
                repository.save(row);
            }
        }
    }

    @Override
    public void discard(String projectId, String userId) {
        repository.deleteAll(repository.findByProjectIdAndOwnerUserIdAndDraftTrue(projectId, userId));
    }

    private static DatatypeDefinitionEntity newRow(String projectId, String userId, String datatypeIri, String expression,
                                                   String definitionType) {
        DatatypeDefinitionEntity row = new DatatypeDefinitionEntity();
        row.setProjectId(projectId);
        row.setDatatypeIri(datatypeIri);
        row.setExpression(expression);
        row.setDefinitionType(definitionType);
        row.setCreatedAt(new Date());
        row.setUpdatedAt(new Date());
        row.setDraft(true);
        row.setOwnerUserId(userId);
        return row;
    }

    private static MutationOp addOp(String datatypeIri, String expression) {
        return new MutationOp("addDatatypeDefinition", datatypeIri, null, null, null, expression,
                null, null, null, null, null, null, null, null, null);
    }

    private static MutationOp deleteOp(String datatypeIri) {
        return new MutationOp("deleteDatatypeDefinition", datatypeIri, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
