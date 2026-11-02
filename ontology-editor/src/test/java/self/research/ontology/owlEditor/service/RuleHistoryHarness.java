package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.query.GraphQueryResult;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.semanticweb.owlapi.model.SWRLRule;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class RuleHistoryHarness implements AutoCloseable {

    static final String P = "p";
    static final String MAIN = "urn:graph:main";
    static final String DRAFT = "urn:graph:draft:u1";
    static final String OWNER = "owner@x.com";
    static final String USER = "u1";

    final ObjectMapper mapper = new ObjectMapper();
    final RuleTestSupport.Store store = new RuleTestSupport.Store();
    final List<HistoryChange> rows = new ArrayList<>();
    final SparqlDatasetService datasets = mock(SparqlDatasetService.class);
    final HistoryChangeRepository repository = mock(HistoryChangeRepository.class);
    final MongoTemplate mongo = mock(MongoTemplate.class);
    final EditorReasonerCacheService reasonerCache = mock(EditorReasonerCacheService.class);
    final OntologyHistoryService history = new OntologyHistoryService(datasets);
    final HistorySyncService sync = new HistorySyncService(repository, mongo, history);
    final RollbackAuditRepository audits = mock(RollbackAuditRepository.class);
    final OntologyMutationService mutations;
    final RawUpdateHistory raw;
    final ChangeRollbackService rollback;
    final RuleService rules;
    final PublishedHistory published;
    final OntologyHistoryService rollbackHistory = mock(OntologyHistoryService.class);
    final List<Map<String, Object>> legacy = new ArrayList<>();
    final List<String> legacyMarked = new ArrayList<>();
    final ProjectWriteLockRegistry locks = new ProjectWriteLockRegistry();
    final RuleImportLedger ledger;

    RuleHistoryHarness() throws Exception {
        this(null);
    }

    RuleHistoryHarness(java.util.function.Function<RuleHistoryHarness, SwrlRuleClient> clients) throws Exception {
        store.put(MAIN, RuleTestSupport.parse(RuleTestSupport.seedTurtle(), RDFFormat.TURTLE));
        store.copy(MAIN, DRAFT);
        wireDatasets();
        wireRepository();
        ReflectionTestUtils.setField(history, "historySyncService", sync);
        ReflectionTestUtils.setField(history, "self", mock(OntologyHistoryService.class));
        ReflectionTestUtils.setField(history, "exactCoverService", new ExactCoverService(mongo));
        raw = new RawUpdateHistory(datasets);
        ReflectionTestUtils.setField(raw, "historyService", history);
        ReflectionTestUtils.setField(raw, "draftTrackingService", mock(DraftTrackingService.class));
        DraftCopyService draftCopy = mock(DraftCopyService.class);
        when(draftCopy.isReady(anyString(), any())).thenReturn(true);
        mutations = new OntologyMutationService(datasets, mock(OntologyIndexService.class), mock(ProjectMetadataService.class),
                mock(GraphGeneratingService.class), mock(TopLevelClassCacheService.class), mock(StorageManager.class), Runnable::run);
        ReflectionTestUtils.setField(mutations, "rawUpdateHistory", raw);
        ReflectionTestUtils.setField(mutations, "historyService", history);
        ReflectionTestUtils.setField(mutations, "draftCopyService", draftCopy);
        ReflectionTestUtils.setField(mutations, "editorReasonerCacheService", reasonerCache);
        when(audits.save(any())).thenAnswer(inv -> {
            RollbackAudit audit = inv.getArgument(0);
            if (audit.getId() == null) {
                audit.setId("audit-" + UUID.randomUUID());
            }
            return audit;
        });
        rollback = new ChangeRollbackService(sync, new RollbackMutationPlanner(),
                new RollbackGraphFacts(datasets, null, null), mutations, audits, rollbackHistory,
                locks, mock(DraftTrackingService.class), null);
        HistoryChangeRepository blockerRepository = repository;
        ReflectionTestUtils.setField(rollback, "blockers", new UndoBlockers(blockerRepository, mock(ProjectImportService.class)));
        published = new PublishedHistory(mongo, datasets, history);
        ledger = new RuleImportLedger(repository, raw, mutations, locks, null);
        rules = new RuleService(clients == null ? client() : clients.apply(this),
                (projectId, draft, user) -> store.ontology(draft ? DRAFT : MAIN),
                mutations::applyRawUpdateWithHistory, ledger);
        ReflectionTestUtils.setField(rollback, "ruleService", rules);
    }

    SwrlRuleClient client() {
        return new SwrlRuleClient((method, url, body, token) -> {
            if (url.contains("/rules/legacy") && "GET".equals(method)) {
                List<Map<String, Object>> open = new ArrayList<>();
                for (Map<String, Object> row : legacy) {
                    if (!legacyMarked.contains(String.valueOf(row.get("id")))) {
                        open.add(row);
                    }
                }
                return new SwrlRuleClient.Answer(200, mapper.writeValueAsString(open));
            }
            if (url.endsWith("/migrated")) {
                String[] parts = url.split("/");
                legacyMarked.add(parts[parts.length - 2]);
                return new SwrlRuleClient.Answer(200, "{}");
            }
            if (url.contains("/rules/embedded")) {
                return new SwrlRuleClient.Answer(200, "[]");
            }
            if (!url.endsWith("/rules/parse")) {
                return new SwrlRuleClient.Answer(200, "[]");
            }
            JsonNode request = mapper.readTree(body);
            String[] parts = request.path("ruleText").asText().split(">");
            Map<String, Object> answer = new HashMap<>();
            answer.put("valid", true);
            answer.put("axiom", RuleTestSupport.functionalRule(parts[0].trim(), parts[1].trim()));
            answer.put("errors", List.of());
            answer.put("suggestions", List.of());
            return new SwrlRuleClient.Answer(200, mapper.writeValueAsString(answer));
        }, "http://swrl", "t");
    }

    private void wireDatasets() {
        when(datasets.graphTarget(P)).thenReturn(new SparqlDatasetService.ProjectGraphTarget(store.repo(), MAIN));
        when(datasets.getDraftGraphUri(eq(P), anyString())).thenReturn(DRAFT);
        when(datasets.getGraphUri(P)).thenReturn(MAIN);
        when(datasets.execConstructAll(P)).thenAnswer(inv -> resultOf(store.read(MAIN)));
        when(datasets.execAsk(eq(P), anyString())).thenAnswer(inv -> ask(inv.getArgument(1), MAIN));
        when(datasets.execAskInGraph(eq(P), anyString(), anyString()))
                .thenAnswer(inv -> ask(inv.getArgument(2), inv.getArgument(1)));
        org.mockito.Mockito.doAnswer(inv -> {
            store.update(MAIN, inv.getArgument(1));
            return null;
        }).when(datasets).execUpdate(eq(P), anyString());
        org.mockito.Mockito.doAnswer(inv -> {
            store.update(DRAFT, inv.getArgument(2));
            return null;
        }).when(datasets).execDraftUpdateCopyOnSwitch(eq(P), anyString(), anyString());
    }

    private boolean ask(String query, String graph) {
        try (RepositoryConnection c = store.repo().getConnection()) {
            var q = c.prepareBooleanQuery(query);
            SimpleDataset dataset = new SimpleDataset();
            dataset.addDefaultGraph(store.graph(graph));
            q.setDataset(dataset);
            return q.evaluate();
        }
    }

    private static GraphQueryResult resultOf(Model model) {
        Iterator<Statement> it = new ArrayList<>(model).iterator();
        return new GraphQueryResult() {
            @Override
            public Map<String, String> getNamespaces() {
                return Map.of();
            }

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public Statement next() {
                return it.next();
            }

            @Override
            public void remove() {
            }

            @Override
            public void close() {
            }
        };
    }

    void wireRepository() {
        when(repository.save(any())).thenAnswer(inv -> {
            HistoryChange change = inv.getArgument(0);
            if (change.getId() == null) {
                change.setId(UUID.randomUUID().toString());
            }
            rows.add(change);
            return change;
        });
        when(repository.existsByProjectIdAndEditId(anyString(), anyString())).thenReturn(false);
        when(repository.findByProjectIdAndChangeSetIdOrderByTimestampDesc(anyString(), anyString())).thenAnswer(inv -> {
            List<HistoryChange> set = new ArrayList<>();
            for (HistoryChange row : rows) {
                if (inv.getArgument(1).equals(row.getChangeSetId())) {
                    set.add(row);
                }
            }
            Collections.reverse(set);
            return set;
        });
        when(repository.findByProjectIdAndImportId(anyString(), anyString())).thenAnswer(inv -> {
            List<HistoryChange> found = new ArrayList<>();
            for (HistoryChange row : rows) {
                if (inv.getArgument(1).equals(row.getMetadata().get("importId"))) {
                    found.add(row);
                }
            }
            return found;
        });
        when(repository.findById(anyString())).thenAnswer(inv -> rows.stream().filter(r -> inv.getArgument(0).equals(r.getId())).findFirst());
        when(repository.findByEditId(anyString())).thenAnswer(inv -> rows.stream().filter(r -> inv.getArgument(0).equals(r.getEditId())).findFirst());
        when(repository.findByProjectId(anyString(), any(Pageable.class))).thenAnswer(inv -> {
            List<HistoryChange> newestFirst = new ArrayList<>(rows);
            Collections.reverse(newestFirst);
            return newestFirst;
        });
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(HistoryChange.class))).thenAnswer(inv -> {
            applyUpdate(inv.getArgument(0), inv.getArgument(1), false);
            return null;
        });
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(HistoryChange.class))).thenAnswer(inv -> {
            applyUpdate(inv.getArgument(0), inv.getArgument(1), true);
            return null;
        });
        when(mongo.find(any(Query.class), eq(HistoryChange.class))).thenAnswer(inv -> {
            List<HistoryChange> draftRows = new ArrayList<>();
            for (HistoryChange row : rows) {
                if (row.isDraft()) {
                    draftRows.add(row);
                }
            }
            return draftRows;
        });
    }

    @SuppressWarnings("unchecked")
    private void applyUpdate(Query query, Update update, boolean multi) {
        Document filter = query.getQueryObject();
        Document body = update.getUpdateObject();
        for (HistoryChange row : rows) {
            if (!matches(row, filter)) {
                continue;
            }
            Document set = (Document) body.get("$set");
            if (set != null) {
                for (Map.Entry<String, Object> entry : set.entrySet()) {
                    switch (entry.getKey()) {
                        case "reverted" -> row.setReverted((Boolean) entry.getValue());
                        case "revertedAuditId" -> row.setRevertedAuditId((String) entry.getValue());
                        case "exactCovers" -> row.setExactCovers((Integer) entry.getValue());
                        case "undoBlockedReason" -> row.setUndoBlockedReason((String) entry.getValue());
                        default -> {
                        }
                    }
                }
            }
            Document unset = (Document) body.get("$unset");
            if (unset != null) {
                for (String key : unset.keySet()) {
                    switch (key) {
                        case "exactDeleted" -> row.setExactDeleted(null);
                        case "exactInserted" -> row.setExactInserted(null);
                        case "coveredBy" -> row.setCoveredBy(null);
                        default -> {
                        }
                    }
                }
            }
            if (!multi) {
                return;
            }
        }
    }

    private static boolean matches(HistoryChange row, Document filter) {
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            Object want = entry.getValue();
            boolean ok = switch (entry.getKey()) {
                case "_id" -> want.equals(row.getId());
                case "projectId" -> want.equals(row.getProjectId());
                case "editId" -> want.equals(row.getEditId());
                case "coveredBy" -> want.equals(row.getCoveredBy());
                default -> true;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    String graphFor(boolean draft) {
        return draft ? DRAFT : MAIN;
    }

    String user(boolean draft) {
        return draft ? USER : OWNER;
    }

    Model graph(boolean draft) {
        return store.read(graphFor(draft));
    }

    Set<SWRLRule> rulesIn(boolean draft) {
        return RuleTestSupport.rulesOf(store.ontology(graphFor(draft)));
    }

    String create(boolean draft, String name, String text) throws Exception {
        return String.valueOf(rules.create(P, draft, user(draft), "Tester",
                new RuleService.RuleInput(name, text, null, null, null), ChangeOrigin.manual()).get("id"));
    }

    void update(boolean draft, String id, RuleService.RuleInput input) throws Exception {
        rules.update(P, id, draft, user(draft), "Tester", input, ChangeOrigin.manual());
    }

    void delete(boolean draft, String id) throws Exception {
        rules.delete(P, id, draft, user(draft), "Tester", ChangeOrigin.manual());
    }

    HistoryChange lastRow() {
        return rows.get(rows.size() - 1);
    }

    String lastSetId() {
        return lastRow().getChangeSetId();
    }

    List<HistoryChange> lastSet() {
        return sync.getChangeSet(P, lastSetId());
    }

    ChangeRollbackService.Actor actor(boolean draft) {
        return new ChangeRollbackService.Actor(user(draft), "Tester");
    }

    ChangeRollbackService.Result undo(String changeSetId, boolean draft) {
        return rollback.undoChangeSet(P, changeSetId, actor(draft), false);
    }

    ChangeRollbackService.Result redo(String changeSetId, boolean draft) {
        return rollback.redoChangeSet(P, changeSetId, actor(draft), false);
    }

    @Override
    public void close() {
        store.close();
    }
}
