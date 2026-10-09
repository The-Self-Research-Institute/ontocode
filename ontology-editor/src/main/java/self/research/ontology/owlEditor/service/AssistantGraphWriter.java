package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import self.research.ontology.owlEditor.document.AssistantApplyOperationDocument;
import self.research.ontology.owlEditor.document.AssistantEditGroupDocument;
import self.research.ontology.owlEditor.service.AssistantEditApplyService.ApplyResult;
import self.research.ontology.owlEditor.util.PerfPhases;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Slf4j
final class AssistantGraphWriter {

    record Written(long sourceVersion, boolean cacheMatchesSubmittedContent, boolean patched) {}

    record Outcome(Written written, ApplyResult failure) {}

    private final AssistantApplyOperationService operationService;
    private final CodeViewReimportPipeline reimportPipeline;
    private final SparqlDatasetService datasetService;
    private final AssistantApplyFailureHandler failureHandler;
    private final TriplePatchPlanner planner = new TriplePatchPlanner();
    private final OwlFormatPatchPlanner owlFormatPlanner = new OwlFormatPatchPlanner();
    private final TriplePatchExecutor executor;

    AssistantGraphWriter(AssistantApplyOperationService operationService, CodeViewReimportPipeline reimportPipeline,
                         SparqlDatasetService datasetService, AssistantApplyFailureHandler failureHandler) {
        this.operationService = operationService;
        this.reimportPipeline = reimportPipeline;
        this.datasetService = datasetService;
        this.failureHandler = failureHandler;
        this.executor = new TriplePatchExecutor(datasetService);
    }

    Outcome write(AssistantEditGroupDocument group, Path sourceFile, Path splicedFile, String userEmail,
                  boolean patchEnabled, PerfPhases perf) {
        return write(group, sourceFile, splicedFile, userEmail, patchEnabled, perf, null);
    }

    Outcome write(AssistantEditGroupDocument group, Path sourceFile, Path splicedFile, String userEmail,
                  boolean patchEnabled, PerfPhases perf, ChangeOrigin origin) {
        if (patchEnabled && !group.isDraft()) {
            Optional<Written> patched = tryPatch(group, sourceFile, splicedFile, userEmail, perf, origin);
            if (patched.isPresent()) {
                return new Outcome(patched.get(), null);
            }
        }
        return reimport(group, splicedFile, userEmail, perf, origin);
    }

    private static void seedIndex(Path cacheFile, TriplePatchPlanner.TriplePatch patch) {
        try {
            CodeViewSubjectIndex.seed(cacheFile, patch.newIndex());
        } catch (Exception seedEx) {
            log.debug("[Assistant] Could not seed the subject index for {}: {}", cacheFile, seedEx.getMessage());
        }
    }

    private Optional<Written> tryPatch(AssistantEditGroupDocument group, Path sourceFile, Path splicedFile,
                                       String userEmail, PerfPhases perf, ChangeOrigin origin) {
        String projectId = group.getProjectId();
        Optional<TriplePatchPlanner.TriplePatch> plan;
        try {
            String graphUri = datasetService.graphTarget(projectId).graphUri();
            plan = OwlFormatPatchPlanner.supports(group.getTargetPath())
                    ? owlFormatPlanner.plan(sourceFile, splicedFile, graphUri)
                    : planner.plan(group.getTargetPath(), sourceFile, splicedFile, edits(group), graphUri);
        } catch (Exception planEx) {
            log.info("[Assistant] Could not plan a triple patch for group {} ({}); reimporting", group.getId(),
                    AssistantApplyFailureHandler.messageOf(planEx));
            plan = Optional.empty();
        }
        perf.mark("patchPlan");
        if (plan.isEmpty()) {
            return Optional.empty();
        }
        TriplePatchPlanner.TriplePatch patch = plan.get();
        AssistantApplyOperationDocument operation = operationService.preparePatch(group, userEmail);
        operationService.markImporting(operation);
        try {
            if (executor.apply(projectId, patch) != TriplePatchExecutor.Outcome.APPLIED) {
                operationService.markRolledBack(operation, "the patched triples did not match the edited document", "PATCH_UNDONE");
                perf.mark("patchUndone");
                return Optional.empty();
            }
            perf.mark("patchApply");
            Model removed = new LinkedHashModel(patch.removed());
            removed.addAll(patch.restoredTrees());
            Model added = new LinkedHashModel(patch.added());
            added.addAll(patch.insertedTrees());
            long version = reimportPipeline.finishPatch(projectId, group.getTargetPath(), splicedFile, userEmail,
                    userEmail, removed, added, origin);
            operationService.markCommitted(operation);
            seedIndex(sourceFile, patch);
            datasetService.captureAndPersistPrefixesFromFile(projectId, splicedFile, group.getTargetPath(),
                    group.isDraft(), group.getDraftUserId());
            perf.mark("patchFinish");
            log.info("[Assistant] Patched {} removed and {} added triples for group {} instead of reimporting",
                    removed.size(), added.size(), group.getId());
            return Optional.of(new Written(version, true, true));
        } catch (Exception patchEx) {
            log.warn("[Assistant] Triple patch for group {} failed ({}); reimporting the whole document", group.getId(),
                    AssistantApplyFailureHandler.messageOf(patchEx));
            operationService.markRolledBack(operation, AssistantApplyFailureHandler.messageOf(patchEx), "PATCH_FAILED_REIMPORTING");
            return Optional.empty();
        }
    }

    private Outcome reimport(AssistantEditGroupDocument group, Path splicedFile, String userEmail, PerfPhases perf,
                             ChangeOrigin origin) {
        AssistantApplyOperationDocument operation;
        try {
            operation = operationService.prepare(group, userEmail);
            perf.mark("snapshot");
        } catch (Exception snapshotEx) {
            log.error("[Assistant] Could not capture a pre-apply snapshot for project {}; not applying group {}: {}",
                    group.getProjectId(), group.getId(), snapshotEx.getMessage(), snapshotEx);
            return new Outcome(null, ApplyResult.builder().ok(false).errorCode("APPLY_FAILED")
                    .message("Couldn't save a copy of the project to roll back to, so the change wasn't applied. "
                            + "Nothing was modified; try again.").build());
        }
        operationService.markImporting(operation);
        boolean draft = group.isDraft();
        String draftUserId = group.getDraftUserId();
        String targetGraphOverride = draft ? datasetService.getDraftGraphUri(group.getProjectId(), draftUserId) : null;
        CodeViewReimportPipeline.ReimportResult result;
        try {
            result = reimportPipeline.reimport(new CodeViewReimportPipeline.ReimportRequest(
                    group.getProjectId(), group.getTargetPath(), splicedFile, draft,
                    draft ? draftUserId : userEmail, userEmail, targetGraphOverride,
                    operationService.snapshotOf(operation), true, origin));
        } catch (Exception reimportEx) {
            perf.mark("reimportFailed");
            ApplyResult failed = failureHandler.handle(group, operation, reimportEx);
            perf.mark("rollback");
            return new Outcome(null, failed);
        }
        perf.mark("reimport");
        operationService.markCommitted(operation);
        return new Outcome(new Written(result.sourceVersion(), result.cacheMatchesSubmittedContent(), false), null);
    }

    private static List<TriplePatchPlanner.Edit> edits(AssistantEditGroupDocument group) {
        return group.getEdits().stream()
                .map(e -> new TriplePatchPlanner.Edit(e.getStartLine(), e.getLineCount(), e.getLineCount() + e.getLineDelta()))
                .toList();
    }
}
