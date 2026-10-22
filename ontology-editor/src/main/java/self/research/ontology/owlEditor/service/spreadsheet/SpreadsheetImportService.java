package self.research.ontology.owlEditor.service.spreadsheet;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.SpreadsheetImportRecord;
import self.research.ontology.owlEditor.repository.SpreadsheetImportRecordRepository;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.OntologyHistoryService;
import self.research.ontology.owlEditor.service.OntologyIndexService;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleSpec;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class SpreadsheetImportService {

    private static final Logger log = LoggerFactory.getLogger(SpreadsheetImportService.class);

    public static final String OPERATION_TYPE = "SPREADSHEET_IMPORT";
    public static final String ENTITY_IRI_PREFIX = "urn:ontocode:spreadsheet-import:";

    static final int MAX_COMMIT_TRIPLES = 80_000;
    private static final int EXISTS_BATCH = 100;
    private static final int DELETE_BATCH = 2_000;
    private static final String DESKTOP_USER_ID = "desktop-user-local";

    private final SpreadsheetParserService parser;
    private final SparqlDatasetService dataset;
    private final OntologyMutationService mutationService;
    private final DraftTrackingService draftTrackingService;
    private final OntologyHistoryService historyService;
    private final ProjectMetadataService metadataService;
    private final SpreadsheetImportRecordRepository records;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    public SpreadsheetImportService(SpreadsheetParserService parser,
                                    SparqlDatasetService dataset,
                                    OntologyMutationService mutationService,
                                    DraftTrackingService draftTrackingService,
                                    OntologyHistoryService historyService,
                                    ProjectMetadataService metadataService,
                                    SpreadsheetImportRecordRepository records) {
        this.parser = parser;
        this.dataset = dataset;
        this.mutationService = mutationService;
        this.draftTrackingService = draftTrackingService;
        this.historyService = historyService;
        this.metadataService = metadataService;
        this.records = records;
    }

    private SpreadsheetImportDraftSupport draftSupport;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDraftSupport(SpreadsheetImportDraftSupport draftSupport) {
        this.draftSupport = draftSupport;
    }

    private SpreadsheetImportDesktopWriter desktopWriter;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDesktopWriter(SpreadsheetImportDesktopWriter desktopWriter) {
        this.desktopWriter = desktopWriter;
    }

    private OntologyIndexService indexService;

    private final Cache<String, ImportItemsSummary.Parsed> parsedItems = Caffeine.newBuilder()
        .maximumSize(50)
        .expireAfterAccess(Duration.ofMinutes(15))
        .build();

    private ObjectProvider<ProjectImportService> projectImports;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setIndexService(OntologyIndexService indexService) {
        this.indexService = indexService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProjectImports(ObjectProvider<ProjectImportService> projectImports) {
        this.projectImports = projectImports;
    }

    private void refreshPublicSummary(String projectId) {
        if (desktopMode || indexService == null) {
            return;
        }
        OntologyIndexService index = indexService;
        CompletableFuture.runAsync(() -> {
            long start = System.nanoTime();
            try {
                Map<String, Object> meta = index.computeMetadata(projectId);
                metadataService.writeMeta(projectId, meta);
                log.info("[SpreadsheetImport] Refreshed the ontology summary for project {} in {} ms",
                    projectId, (System.nanoTime() - start) / 1_000_000);
                ProjectImportService notifier = projectImports == null ? null : projectImports.getIfAvailable();
                if (notifier != null) {
                    notifier.notifyMetadataReady(projectId);
                }
            } catch (RuntimeException e) {
                log.warn("[SpreadsheetImport] Could not refresh the ontology summary for project {}: {}",
                    projectId, e.getMessage());
            }
        });
    }

    public boolean applyOnDesktop(String projectId, List<String> updates) {
        return desktopWriter != null && desktopWriter.apply(projectId, updates);
    }

    public GenerationResult generate(String projectId, InputStream file, String fileName, List<RuleSpec> rules) {
        if (desktopWriter != null) {
            desktopWriter.syncBeforeRead(projectId);
        }
        long start = System.nanoTime();
        SpreadsheetWorkbook workbook = parser.parse(file, fileName);
        GenerationResult result = AxiomGenerator.generate(workbook, rules, new SparqlOntologyLookup(dataset, projectId));
        log.info("[SpreadsheetImport] Generated {} axioms ({} ops, {} issues) from '{}' for project {} in {} ms",
            result.axioms().size(), result.ops().size(), result.issues().size(), fileName, projectId,
            (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    public static class ImportRejectedException extends RuntimeException {
        private final GenerationResult result;

        public ImportRejectedException(String message, GenerationResult result) {
            super(message);
            this.result = result;
        }

        public GenerationResult result() {
            return result;
        }
    }

    public record CommitResult(String importId, int axiomCount, int triplesAdded, int alreadyPresent, boolean draft) {
    }

    public CommitResult commit(String projectId, InputStream file, String fileName, List<RuleSpec> rules,
                               boolean draft, String userId, String username) {
        GenerationResult result = generate(projectId, file, fileName, rules);
        if (result.hasErrors()) {
            throw new ImportRejectedException("The import has errors. Fix them and preview again before importing.", result);
        }
        if (result.ops().isEmpty()) {
            throw new ImportRejectedException("The rules did not produce anything to import.", result);
        }

        String actor = userId != null && !userId.isBlank() ? userId : "anonymous";
        if (desktopMode) {
            actor = DESKTOP_USER_ID;
        }
        String actorName = username != null && !username.isBlank() ? username : "Anonymous";
        boolean toDraft = draft;
        if (!toDraft && !desktopMode && metadataService.isRequireDraftForMembers(projectId)) {
            String owner = metadataService.getOwnerEmail(projectId).orElse(null);
            if (!actor.equals(owner)) {
                toDraft = true;
            }
        }

        Set<String> triples = triplesOf(result);
        if (triples.size() > MAX_COMMIT_TRIPLES) {
            throw new ImportRejectedException("This import would add " + triples.size() + " triples; the limit for one import is "
                + MAX_COMMIT_TRIPLES + ". Split the file and import it in parts.", result);
        }
        Set<String> alreadyPresent = existing(projectId, triples);
        List<String> added = triples.stream().filter(t -> !alreadyPresent.contains(t)).toList();
        if (added.isEmpty()) {
            throw new ImportRejectedException(NOTHING_NEW, result);
        }

        long start = System.nanoTime();
        String draftSessionId = null;
        if (toDraft) {
            mutationService.applyDraft(projectId, actor, result.ops());
            draftSessionId = SpreadsheetImportDraftSupport.SESSION_PREFIX + UUID.randomUUID();
        } else {
            mutationService.apply(projectId, result.ops());
        }

        SpreadsheetImportRecord record = new SpreadsheetImportRecord();
        record.setProjectId(projectId);
        record.setUserId(actor);
        record.setUsername(actorName);
        record.setFileName(fileName);
        record.setDraft(toDraft);
        record.setDraftSessionId(draftSessionId);
        record.setCreatedAt(Instant.now());
        record.setAxiomCount(result.axioms().size());
        record.setAlreadyPresentCount(alreadyPresent.size());
        record.setAddedTriples(new ArrayList<>(added));
        String savedId = null;
        try {
            if (draftSessionId != null) {
                draftTrackingService.recordDrafts(projectId, actor, actorName, result.ops(), draftSessionId);
            }
            record = records.save(record);
            savedId = record.getId();
            String description = "Imported " + result.axioms().size() + " axiom" + (result.axioms().size() == 1 ? "" : "s")
                + " from " + fileName + " (" + result.rowsProcessed() + " row" + (result.rowsProcessed() == 1 ? "" : "s") + ")";
            historyService.recordEdit(projectId, actor, actorName, OPERATION_TYPE, ENTITY_IRI_PREFIX + record.getId(),
                fileName, null, null, description, null, null, toDraft);
        } catch (RuntimeException e) {
            takeBack(projectId, added, toDraft, actor, draftSessionId, savedId);
            throw new IllegalStateException("The import could not be recorded, so it was taken back and nothing was changed ("
                + e.getMessage() + ")", e);
        }

        log.info("[SpreadsheetImport] Committed import {} to project {} ({}): {} axioms, {} triples added, {} already present, {} ms",
            record.getId(), projectId, toDraft ? "draft" : "main", result.axioms().size(), added.size(),
            alreadyPresent.size(), (System.nanoTime() - start) / 1_000_000);
        if (!toDraft) {
            refreshPublicSummary(projectId);
        }
        return new CommitResult(record.getId(), result.axioms().size(), added.size(), alreadyPresent.size(), toDraft);
    }

    static final String NOTHING_NEW = "Everything this file would add is already in the ontology. Nothing to import.";

    public record Novelty(int triples, int alreadyPresent) {
        public boolean nothingNew() {
            return triples > 0 && alreadyPresent >= triples;
        }
    }

    public Novelty novelty(String projectId, GenerationResult result) {
        Set<String> triples = triplesOf(result);
        if (triples.isEmpty() || triples.size() > MAX_COMMIT_TRIPLES) {
            return new Novelty(triples.size(), 0);
        }
        return new Novelty(triples.size(), existing(projectId, triples).size());
    }

    private static Set<String> triplesOf(GenerationResult result) {
        Set<String> triples = new LinkedHashSet<>();
        for (MutationOp op : result.ops()) {
            triples.addAll(ImportTriples.of(op));
        }
        return triples;
    }

    private Set<String> existing(String projectId, Set<String> triples) {
        Set<String> found = new HashSet<>();
        List<String> list = new ArrayList<>(triples);
        for (int from = 0; from < list.size(); from += EXISTS_BATCH) {
            List<String> batch = list.subList(from, Math.min(from + EXISTS_BATCH, list.size()));
            StringBuilder q = new StringBuilder("SELECT DISTINCT ?i WHERE {\n");
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) {
                    q.append("  UNION\n");
                }
                q.append("  { ").append(batch.get(i)).append(" . BIND(").append(i).append(" AS ?i) }\n");
            }
            q.append("}");
            try (TupleQueryResult rs = dataset.execSelect(projectId, q.toString())) {
                while (rs.hasNext()) {
                    found.add(batch.get(Integer.parseInt(rs.next().getValue("i").stringValue())));
                }
            }
        }
        return found;
    }

    public record RollbackResult(String fileName, int triplesRemoved, int axiomCount) {
    }

    public static boolean isImportEntry(String operationType) {
        return OPERATION_TYPE.equals(operationType);
    }

    public List<String> rollbackUpdates(String projectId, String entityIri, boolean undo) {
        Optional<SpreadsheetImportRecord> record = findRecord(projectId, entityIri);
        if (record.isEmpty()) {
            return null;
        }
        return updates(record.get().getAddedTriples(), undo);
    }

    private static List<String> updates(List<String> triples, boolean delete) {
        List<String> updates = new ArrayList<>();
        for (int from = 0; from < triples.size(); from += DELETE_BATCH) {
            List<String> batch = triples.subList(from, Math.min(from + DELETE_BATCH, triples.size()));
            updates.add((delete ? "DELETE DATA {\n" : "INSERT DATA {\n") + String.join(" .\n", batch) + " .\n}");
        }
        return updates;
    }

    private void takeBack(String projectId, List<String> added, boolean toDraft, String actor,
                          String draftSessionId, String savedId) {
        try {
            List<String> deletes = updates(added, true);
            if (toDraft || desktopWriter == null || !desktopWriter.apply(projectId, deletes)) {
                for (String sparql : deletes) {
                    mutationService.applyRawUpdate(projectId, sparql, toDraft, actor);
                }
            }
            if (draftSessionId != null && draftSupport != null) {
                draftSupport.removeDraftChanges(projectId, actor, draftSessionId);
            }
            if (savedId != null) {
                records.deleteById(savedId);
            }
            log.warn("[SpreadsheetImport] Import into project {} could not be recorded; its {} triples were taken back",
                projectId, added.size());
        } catch (RuntimeException e) {
            log.error("[SpreadsheetImport] Import into project {} could not be recorded and taking it back failed",
                projectId, e);
        }
    }

    public void markUndone(String projectId, String entityIri, boolean undone, String actingUser) {
        findRecord(projectId, entityIri).ifPresent(record -> {
            record.setRolledBack(undone);
            record.setRolledBackAt(undone ? Instant.now() : null);
            record.setRolledBackBy(undone ? actingUser : null);
            records.save(record);
            log.info("[SpreadsheetImport] Import {} in project {} marked {}", record.getId(), projectId,
                undone ? "undone" : "redone");
            if (record.isDraft() && draftSupport != null) {
                if (undone) {
                    draftSupport.discardDraftChanges(projectId, record.getUserId(), record.getDraftSessionId());
                } else {
                    draftSupport.restoreDraftChanges(projectId, record.getUserId(), record.getDraftSessionId());
                }
            }
            if (!record.isDraft()) {
                refreshPublicSummary(projectId);
            }
        });
    }

    public RollbackResult rollback(String projectId, String entityIri, String actingUser) {
        SpreadsheetImportRecord record = findRecord(projectId, entityIri)
            .orElseThrow(() -> new IllegalArgumentException("The record of this import was not found, so it cannot be rolled back"));
        if (record.isRolledBack()) {
            return new RollbackResult(record.getFileName(), 0, record.getAxiomCount());
        }
        for (String sparql : rollbackUpdates(projectId, entityIri, true)) {
            mutationService.applyRawUpdate(projectId, sparql, record.isDraft(), record.getUserId());
        }
        markUndone(projectId, entityIri, true, actingUser);
        return new RollbackResult(record.getFileName(), record.getAddedTriples().size(), record.getAxiomCount());
    }

    public record ImportedItems(String fileName, int axiomCount, boolean draft, boolean rolledBack,
                                Map<String, ImportItemsSummary.Group> groups) {
    }

    public Optional<ImportedItems> importedItems(String projectId, String importId, String callerUserId, int limit) {
        return findRecord(projectId, ENTITY_IRI_PREFIX + importId)
            .filter(r -> !r.isDraft() || (callerUserId != null && callerUserId.equals(r.getUserId())))
            .map(r -> new ImportedItems(r.getFileName(), r.getAxiomCount(), r.isDraft(), r.isRolledBack(),
                parsedItems(r).summary(limit)));
    }

    public Optional<ImportItemsSummary.Group> importedItemsPage(String projectId, String importId, String callerUserId,
                                                                 String group, int offset, int limit) {
        return findRecord(projectId, ENTITY_IRI_PREFIX + importId)
            .filter(r -> !r.isDraft() || (callerUserId != null && callerUserId.equals(r.getUserId())))
            .map(r -> parsedItems(r).pageOf(group, offset, limit));
    }

    private ImportItemsSummary.Parsed parsedItems(SpreadsheetImportRecord record) {
        return parsedItems.get(record.getId(), id -> ImportItemsSummary.parse(record.getAddedTriples()));
    }

    private Optional<SpreadsheetImportRecord> findRecord(String projectId, String entityIri) {
        String id = entityIri != null && entityIri.startsWith(ENTITY_IRI_PREFIX)
            ? entityIri.substring(ENTITY_IRI_PREFIX.length()) : null;
        Optional<SpreadsheetImportRecord> found = id == null ? Optional.empty() : records.findById(id);
        return found.filter(r -> projectId.equals(r.getProjectId()));
    }
}
