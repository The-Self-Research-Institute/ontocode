package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.SWRLRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import self.research.ontology.common.rules.DisabledRules;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.util.OwlAxiomSparqlWriter;

import org.eclipse.rdf4j.model.Model;
import self.research.ontology.common.preview.PreviewStamp;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.spreadsheet.RuleImportFile;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class RuleService {

    private static final String HASH_PREFIX = "rule-";

    @FunctionalInterface
    public interface OntologyLoader {
        OWLOntology load(String projectId, boolean draft, String userId) throws Exception;
    }

    @FunctionalInterface
    public interface Applier {
        void apply(String projectId, String sparql, boolean draft, String userId, String username, ChangeOrigin origin);
    }

    public interface ImportSupport {
        <T> T exclusive(String projectId, java.util.concurrent.Callable<T> work) throws Exception;

        List<HistoryChange> byImportId(String projectId, String importId);

        boolean recorded(String projectId, String changeSetId);

        Model snapshot(String projectId, boolean draft, String userId);

        void restore(String projectId, boolean draft, String userId, Model before);

        PreviewStamp stamp(String projectId, boolean draft, String userId);
    }

    static final ImportSupport UNSUPPORTED = new ImportSupport() {
        @Override
        public <T> T exclusive(String projectId, java.util.concurrent.Callable<T> work) throws Exception {
            return work.call();
        }

        @Override
        public List<HistoryChange> byImportId(String projectId, String importId) {
            return List.of();
        }

        @Override
        public boolean recorded(String projectId, String changeSetId) {
            return true;
        }

        @Override
        public Model snapshot(String projectId, boolean draft, String userId) {
            return null;
        }

        @Override
        public void restore(String projectId, boolean draft, String userId, Model before) {
        }

        @Override
        public PreviewStamp stamp(String projectId, boolean draft, String userId) {
            return null;
        }
    };

    public static class ImportStaleException extends RuntimeException {
        private final transient RuleImportReport report;

        public ImportStaleException(RuleImportReport report) {
            super(report.message());
            this.report = report;
        }

        public RuleImportReport report() {
            return report;
        }
    }

    public static class ImportConflictException extends RuntimeException {
        public ImportConflictException(String message) {
            super(message);
        }
    }

    public static class ImportForbiddenException extends RuntimeException {
        public ImportForbiddenException(String message) {
            super(message);
        }
    }

    public static class ImportFailedException extends RuntimeException {
        public ImportFailedException(String message) {
            super(message);
        }
    }

    public record RuleInput(String ruleName, String ruleText, String comment, String category, Boolean enabled) {
    }

    public static class InvalidRuleException extends RuntimeException {
        private final List<String> errors;
        private final List<String> suggestions;

        public InvalidRuleException(String message, List<String> errors, List<String> suggestions) {
            super(message);
            this.errors = errors;
            this.suggestions = suggestions;
        }

        public List<String> errors() {
            return errors;
        }

        public List<String> suggestions() {
            return suggestions;
        }
    }

    public static class RuleNameTakenException extends RuntimeException {
        public RuleNameTakenException(String name) {
            super("A rule named \"" + name + "\" already exists. Choose a different name.");
        }
    }

    public static class RuleAmbiguousException extends RuntimeException {
        public RuleAmbiguousException(String name) {
            super("More than one rule without an id has the same logic" + (name == null ? "" : " as \"" + name + "\"")
                    + ", so Ontocode cannot tell which one you mean. Edit or delete the extra copy in Code View first.");
        }
    }

    public static class RuleNotFoundException extends RuntimeException {
        public RuleNotFoundException(String id) {
            super("No rule with this id exists in the ontology: " + id);
        }
    }

    private final SwrlRuleClient client;
    private final OntologyLoader loader;
    private final Applier applier;
    private final RuleImporter importer;

    @Autowired
    public RuleService(SwrlRuleClient client, StorageManager storageManager, SparqlDatasetService datasetService,
                       @Lazy OntologyMutationService mutationService,
                       @Autowired(required = false) ProjectOntologyCache ontologyCache,
                       @Autowired(required = false) RuleImportLedger ledger) {
        this(client, defaultLoader(storageManager, datasetService, ontologyCache), mutationService::applyRawUpdateWithHistory,
                ledger == null ? UNSUPPORTED : ledger);
    }

    public RuleService(SwrlRuleClient client, OntologyLoader loader, Applier applier) {
        this(client, loader, applier, UNSUPPORTED);
    }

    public RuleService(SwrlRuleClient client, OntologyLoader loader, Applier applier, ImportSupport support) {
        this.client = client;
        this.loader = loader;
        this.applier = applier;
        this.importer = new RuleImporter(this, client, loader, applier, support);
    }

    public RuleImportReport importRules(String projectId, boolean draft, String userId, String username,
                                        RuleImportFile file, String importId, String previewStamp, boolean dryRun,
                                        ChangeOrigin origin, java.util.function.Supplier<String> permission)
            throws Exception {
        requireDraftUser(draft, userId);
        return importer.run(projectId, draft, userId, username, file, importId, previewStamp, dryRun, origin, permission);
    }

    public RuleImporter.Export exportRules(String projectId, boolean draft, String userId) throws Exception {
        requireDraftUser(draft, userId);
        return importer.export(projectId, draft, userId);
    }

    public Optional<String> importBlocker(String projectId, boolean draft, String userId, String importedRules)
            throws Exception {
        return importer.blocker(projectId, draft, userId, importedRules);
    }

    private static OntologyLoader defaultLoader(StorageManager storageManager, SparqlDatasetService datasetService,
                                                ProjectOntologyCache ontologyCache) {
        return (projectId, draft, userId) -> {
            if (draft && userId != null && !userId.isBlank()) {
                String graph = datasetService.getDraftGraphUri(projectId, userId);
                String rdf = datasetService.exportNamedGraph(projectId, graph, org.eclipse.rdf4j.rio.RDFFormat.RDFXML);
                return loadTolerant(rdf);
            }
            if (ontologyCache != null) {
                Optional<ProjectOntologyCache.CachedOntology> cached = ontologyCache.get(projectId);
                if (cached.isPresent()) {
                    return cached.get().ontology();
                }
            }
            Path export = storageManager.exportOntology(projectId, "rdfxml");
            return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(export.toFile());
        };
    }

    static OWLOntology loadTolerant(String text) throws Exception {
        OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(text), config);
    }

    public List<Map<String, Object>> list(String projectId, boolean draft, String userId) {
        return client.listEmbedded(projectId, draft, userId);
    }

    public Map<String, Object> create(String projectId, boolean draft, String userId, String username,
                                      RuleInput input, ChangeOrigin origin) throws Exception {
        requireDraftUser(draft, userId);
        String name = requireText(input.ruleName(), "A rule needs a name");
        String text = requireText(input.ruleText(), "A rule needs rule text");
        SWRLRule parsed = parse(projectId, name, text);
        OWLOntology ontology = loader.load(projectId, draft, userId);
        ensureNameFree(ontology, name, Set.of());
        String id = UUID.randomUUID().toString();
        boolean enabled = input.enabled() == null || input.enabled();
        String comment = clean(input.comment());
        String category = clean(input.category());
        SWRLRule rule = annotated(parsed, id, name, comment, category, enabled, Set.of());
        applier.apply(projectId, OwlAxiomSparqlWriter.toInsertData(Set.of(rule)), draft, userId, username,
                noted(origin, name, ChangeOrigin.RuleNote.ADDED));
        return view(id, name, text, comment, category, enabled);
    }

    public Map<String, Object> update(String projectId, String ruleId, boolean draft, String userId, String username,
                                      RuleInput input, ChangeOrigin origin) throws Exception {
        requireDraftUser(draft, userId);
        OWLOntology ontology = loader.load(projectId, draft, userId);
        Resolved resolved = resolve(projectId, ontology, ruleId, draft, userId);
        Set<SWRLRule> existing = resolved.axioms();
        SWRLRule old = existing.iterator().next();
        String id = resolved.adopted() ? UUID.randomUUID().toString() : ruleId;
        String oldName = SwrlRuleKeys.name(old).orElse(resolved.listedName() != null ? resolved.listedName() : "Rule");
        String name = input.ruleName() != null && !input.ruleName().isBlank() ? input.ruleName().trim() : oldName;
        boolean renamed = (SwrlRuleKeys.name(old).isPresent() || resolved.listedName() != null) && !name.equals(oldName);
        String comment = input.comment() != null ? clean(input.comment()) : commentOf(old);
        String category = input.category() != null ? clean(input.category()) : annotationText(old, DisabledRules.CATEGORY);
        boolean enabled = input.enabled() != null ? input.enabled() : !DisabledRules.isDisabled(old);
        ensureNameFree(ontology, name, existing);
        String text = input.ruleText() == null || input.ruleText().isBlank() ? null : input.ruleText();
        SWRLRule base = text == null ? old.getAxiomWithoutAnnotations() : parse(projectId, name, text);
        SWRLRule updated = annotated(base, id, name, comment, category, enabled, preserved(old));
        String sparql = OwlAxiomSparqlWriter.toDeleteWhere(new LinkedHashSet<>(existing)) + ";\n"
                + OwlAxiomSparqlWriter.toInsertData(Set.of(updated));
        boolean onlyToggled = input.enabled() != null && text == null && isBlank(input.ruleName())
                && input.comment() == null && input.category() == null;
        String action = !onlyToggled ? ChangeOrigin.RuleNote.CHANGED
                : enabled ? ChangeOrigin.RuleNote.TURNED_ON : ChangeOrigin.RuleNote.TURNED_OFF;
        ChangeOrigin.RuleNote note = renamed
                ? new ChangeOrigin.RuleNote(name, ChangeOrigin.RuleNote.RENAMED,
                        "Rule \"" + oldName + "\" renamed to \"" + name + "\"")
                : new ChangeOrigin.RuleNote(name, action);
        applier.apply(projectId, sparql, draft, userId, username,
                (origin == null ? ChangeOrigin.manual() : origin).withRule(note));
        return view(id, name, text, comment, category, enabled);
    }

    public void delete(String projectId, String ruleId, boolean draft, String userId, String username,
                       ChangeOrigin origin) throws Exception {
        requireDraftUser(draft, userId);
        OWLOntology ontology = loader.load(projectId, draft, userId);
        Resolved resolved = resolve(projectId, ontology, ruleId, draft, userId);
        Set<SWRLRule> existing = resolved.axioms();
        String name = SwrlRuleKeys.name(existing.iterator().next())
                .orElse(resolved.listedName() != null ? resolved.listedName() : "Rule");
        applier.apply(projectId, OwlAxiomSparqlWriter.toDeleteWhere(new LinkedHashSet<>(existing)), draft, userId,
                username, noted(origin, name, ChangeOrigin.RuleNote.DELETED));
    }

    private static ChangeOrigin noted(ChangeOrigin origin, String name, String action) {
        ChangeOrigin base = origin == null ? ChangeOrigin.manual() : origin;
        return base.withRule(new ChangeOrigin.RuleNote(name, action));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireDraftUser(boolean draft, String userId) {
        if (draft && isBlank(userId)) {
            throw new IllegalArgumentException("Sign in to change rules in your draft");
        }
    }

    private record Resolved(Set<SWRLRule> axioms, boolean adopted, String listedName) {
    }

    private Resolved resolve(String projectId, OWLOntology ontology, String ruleId, boolean draft, String userId) {
        Set<SWRLRule> annotated = SwrlRuleKeys.rulesFor(ontology, SwrlRuleKeys.keyOf(ruleId));
        if (!annotated.isEmpty()) {
            return new Resolved(annotated, false, null);
        }
        if (ruleId == null || !ruleId.startsWith(HASH_PREFIX)) {
            throw new RuleNotFoundException(ruleId);
        }
        List<SWRLRule> candidates = ontology.axioms(AxiomType.SWRL_RULE)
                .filter(rule -> SwrlRuleKeys.ruleId(rule).isEmpty()).toList();
        for (SWRLRule candidate : candidates) {
            if (ruleId.equals(UnannotatedRules.hashId(candidate))) {
                Set<SWRLRule> same = sameAxiom(candidates, candidate);
                if (same.size() > 1) {
                    throw new RuleAmbiguousException(SwrlRuleKeys.name(candidate).orElse(null));
                }
                return new Resolved(same, true, null);
            }
        }
        Map<String, Object> listed = client.listEmbedded(projectId, draft, userId).stream()
                .filter(item -> ruleId.equals(String.valueOf(item.get("id")))).findFirst()
                .orElseThrow(() -> new RuleNotFoundException(ruleId));
        Object text = listed.get("ruleText");
        if (text == null || String.valueOf(text).isBlank()) {
            throw new RuleNotFoundException(ruleId);
        }
        SWRLRule parsed;
        try {
            parsed = parse(projectId, String.valueOf(listed.get("ruleName")), String.valueOf(text));
        } catch (Exception e) {
            throw new RuleNotFoundException(ruleId);
        }
        String wanted = UnannotatedRules.shape(parsed);
        List<SWRLRule> matches = candidates.stream().filter(c -> UnannotatedRules.shape(c).equals(wanted)).toList();
        if (matches.isEmpty()) {
            throw new RuleNotFoundException(ruleId);
        }
        Object name = listed.get("ruleName");
        if (matches.size() > 1) {
            throw new RuleAmbiguousException(name == null ? null : String.valueOf(name));
        }
        return new Resolved(new LinkedHashSet<>(matches), true, name == null ? null : String.valueOf(name));
    }

    private static Set<SWRLRule> sameAxiom(List<SWRLRule> candidates, SWRLRule hit) {
        Set<SWRLRule> same = new LinkedHashSet<>();
        for (SWRLRule candidate : candidates) {
            if (candidate.getAxiomWithoutAnnotations().equals(hit.getAxiomWithoutAnnotations())) {
                same.add(candidate);
            }
        }
        return same;
    }

    public record Built(String id, SWRLRule axiom) {
    }

    public Built buildNew(String projectId, RuleInput input) throws Exception {
        String name = requireText(input.ruleName(), "A rule needs a name");
        String text = requireText(input.ruleText(), "A rule needs rule text");
        SWRLRule parsed = parse(projectId, name, text);
        String id = UUID.randomUUID().toString();
        boolean enabled = input.enabled() == null || input.enabled();
        return new Built(id, annotated(parsed, id, name, clean(input.comment()), clean(input.category()), enabled, Set.of()));
    }

    public OWLOntology load(String projectId, boolean draft, String userId) throws Exception {
        return loader.load(projectId, draft, userId);
    }

    public void apply(String projectId, Set<SWRLRule> axioms, boolean draft, String userId, String username,
                      ChangeOrigin origin) throws Exception {
        requireDraftUser(draft, userId);
        applier.apply(projectId, OwlAxiomSparqlWriter.toInsertData(axioms), draft, userId, username, origin);
    }

    public static boolean hasRuleNamed(OWLOntology ontology, String name) {
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        return ontology.axioms(AxiomType.SWRL_RULE)
                .anyMatch(rule -> SwrlRuleKeys.name(rule)
                        .filter(existing -> existing.trim().toLowerCase(Locale.ROOT).equals(wanted)).isPresent());
    }

    public static boolean hasRuleAxiom(OWLOntology ontology, SWRLRule built) {
        String id = SwrlRuleKeys.ruleId(built).orElse(null);
        return ontology.axioms(AxiomType.SWRL_RULE)
                .anyMatch(rule -> id != null && SwrlRuleKeys.ruleId(rule).filter(id::equals).isPresent());
    }

    public List<Map<String, String>> disabledRules(String projectId, boolean draft, String userId) {
        List<Map<String, String>> disabled = new java.util.ArrayList<>();
        for (Map<String, Object> rule : client.listEmbedded(projectId, draft, userId)) {
            if (Boolean.FALSE.equals(rule.get("enabled"))) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("id", String.valueOf(rule.get("id")));
                entry.put("ruleName", String.valueOf(rule.get("ruleName")));
                disabled.add(entry);
            }
        }
        return disabled;
    }

    SWRLRule parse(String projectId, String name, String text) throws Exception {
        SwrlRuleClient.Parsed parsed = client.parse(projectId, name, text);
        if (!parsed.valid() || parsed.axiom() == null || parsed.axiom().isBlank()) {
            String message = parsed.errors().isEmpty() ? "The rule text is not valid SWRL." : parsed.errors().get(0);
            throw new InvalidRuleException(message, parsed.errors(), parsed.suggestions());
        }
        OWLOntology holder = loadTolerant(parsed.axiom());
        List<SWRLRule> rules = holder.axioms(AxiomType.SWRL_RULE).toList();
        if (rules.size() != 1) {
            throw new InvalidRuleException("The rule text must describe exactly one rule.",
                    List.of("The rule text must describe exactly one rule."), List.of());
        }
        return rules.get(0).getAxiomWithoutAnnotations();
    }

    static SWRLRule annotated(SWRLRule base, String id, String name, String comment, String category,
                                      boolean enabled, Set<OWLAnnotation> extra) {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        Set<OWLAnnotation> annotations = new LinkedHashSet<>();
        annotations.add(df.getOWLAnnotation(df.getRDFSLabel(), df.getOWLLiteral(name)));
        if (comment != null && !comment.isBlank()) {
            annotations.add(df.getOWLAnnotation(df.getRDFSComment(), df.getOWLLiteral(comment)));
        }
        if (category != null && !category.isBlank()) {
            annotations.add(df.getOWLAnnotation(df.getOWLAnnotationProperty(DisabledRules.CATEGORY), df.getOWLLiteral(category)));
        }
        annotations.add(df.getOWLAnnotation(df.getOWLAnnotationProperty(DisabledRules.RULE_ID), df.getOWLLiteral(id)));
        if (!enabled) {
            annotations.add(df.getOWLAnnotation(df.getOWLAnnotationProperty(DisabledRules.ENABLED), df.getOWLLiteral(false)));
        }
        annotations.addAll(extra);
        return df.getSWRLRule(base.body().toList(), base.head().toList(), annotations);
    }

    private static Set<OWLAnnotation> preserved(SWRLRule old) {
        Set<OWLAnnotation> keep = new LinkedHashSet<>();
        for (OWLAnnotation annotation : old.annotationsAsList()) {
            IRI property = annotation.getProperty().getIRI();
            if (annotation.getProperty().isLabel() || annotation.getProperty().isComment()
                    || DisabledRules.CATEGORY.equals(property) || DisabledRules.RULE_ID.equals(property)
                    || DisabledRules.ENABLED.equals(property) || DisabledRules.SWRLA_ENABLED.equals(property)) {
                continue;
            }
            keep.add(annotation);
        }
        return keep;
    }

    static String commentOf(SWRLRule rule) {
        for (OWLAnnotation annotation : rule.annotationsAsList()) {
            if (annotation.getProperty().isComment() && annotation.getValue() instanceof OWLLiteral literal) {
                return literal.getLiteral();
            }
        }
        return null;
    }

    static String annotationText(SWRLRule rule, IRI property) {
        for (OWLAnnotation annotation : rule.annotationsAsList()) {
            if (property.equals(annotation.getProperty().getIRI()) && annotation.getValue() instanceof OWLLiteral literal) {
                return literal.getLiteral();
            }
        }
        return null;
    }

    private static void ensureNameFree(OWLOntology ontology, String name, Set<SWRLRule> own) {
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (SWRLRule rule : ontology.axioms(AxiomType.SWRL_RULE).toList()) {
            if (own.contains(rule)) {
                continue;
            }
            Optional<String> existing = SwrlRuleKeys.name(rule);
            if (existing.isPresent() && existing.get().trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                throw new RuleNameTakenException(name.trim());
            }
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, Object> view(String id, String name, String text, String comment, String category,
                                            boolean enabled) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        view.put("ruleName", name);
        view.put("ruleText", text);
        view.put("comment", comment);
        view.put("category", category);
        view.put("enabled", enabled);
        view.put("source", null);
        view.put("readOnly", false);
        return view;
    }
}
