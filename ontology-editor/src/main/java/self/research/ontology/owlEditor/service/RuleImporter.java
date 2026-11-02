package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.semanticweb.owlapi.formats.PrefixDocumentFormat;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import self.research.ontology.common.preview.PreviewStamp;
import self.research.ontology.common.rules.DisabledRules;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.RuleImportReport.Imported;
import self.research.ontology.owlEditor.service.RuleImportReport.Skipped;
import self.research.ontology.owlEditor.service.RuleService.ImportSupport;
import self.research.ontology.owlEditor.service.spreadsheet.RuleImportFile;
import self.research.ontology.owlEditor.service.spreadsheet.RuleSpreadsheet;
import self.research.ontology.owlEditor.util.OwlAxiomSparqlWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuleImporter {

    private static final Logger log = LoggerFactory.getLogger(RuleImporter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern UNKNOWN = Pattern.compile("Unknown class or property '([^']+)'");
    private static final Pattern PREFIXED = Pattern.compile("(?<![\\w:/#.?-])([A-Za-z_][\\w.-]*):([A-Za-z_][\\w.-]*)");
    private static final Pattern BARE = Pattern.compile("(?<![\\w:?.#/-])([A-Za-z_][\\w-]*)\\s*\\(");
    private static final Set<String> BUILT_IN_WORDS = Set.of("sameAs", "differentFrom");
    private static final Pattern IMPORT_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    public record Export(List<RuleSpreadsheet.ExportRule> rules, Map<String, String> prefixes, int leftOut) {
    }

    private record Accepted(RuleImportFile.Row row, String id, String name, SWRLRule axiom) {
    }

    private record Validation(List<Accepted> accepted, List<Skipped> skipped, int read) {
    }

    record Stamp(PreviewStamp version, String file, String outcome, int imported, int duplicates, int invalid) {
    }

    record Version(String id, String name, String hash) {
    }

    private final RuleService rules;
    private final SwrlRuleClient client;
    private final RuleService.OntologyLoader loader;
    private final RuleService.Applier applier;
    private final ImportSupport support;

    RuleImporter(RuleService rules, SwrlRuleClient client, RuleService.OntologyLoader loader,
                 RuleService.Applier applier, ImportSupport support) {
        this.rules = rules;
        this.client = client;
        this.loader = loader;
        this.applier = applier;
        this.support = support;
    }

    RuleImportReport run(String projectId, boolean draft, String userId, String username, RuleImportFile file,
                         String importId, String previewStamp, boolean dryRun, ChangeOrigin origin,
                         Supplier<String> permission) throws Exception {
        if (!dryRun && (importId == null || !IMPORT_ID.matcher(importId).matches())) {
            throw new IllegalArgumentException("The import needs an importId (a UUID) so a retry cannot import twice");
        }
        refuseIfForbidden(permission);
        if (!dryRun) {
            Optional<RuleImportReport> replay = replay(projectId, importId, file);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        Validation checked = validate(projectId, draft, userId, file);
        if (dryRun) {
            return report(checked, true, stampOf(projectId, draft, userId, file, checked), null);
        }
        if (previewStamp != null && !previewStamp.isBlank()) {
            guardStale(projectId, draft, userId, file, checked, previewStamp);
        }
        if (checked.accepted().isEmpty()) {
            return report(checked, false, null, null);
        }
        return support.exclusive(projectId, () -> commit(projectId, draft, userId, username, file, importId, checked,
                origin == null ? ChangeOrigin.manual() : origin, permission));
    }

    private static void refuseIfForbidden(Supplier<String> permission) {
        String refusal = permission == null ? null : permission.get();
        if (refusal != null) {
            throw new RuleService.ImportForbiddenException(refusal);
        }
    }

    private Optional<RuleImportReport> replay(String projectId, String importId, RuleImportFile file) throws Exception {
        for (HistoryChange earlier : support.byImportId(projectId, importId)) {
            Map<String, String> meta = earlier.getMetadata();
            if (meta == null || !importId.equals(meta.get("importId"))) {
                continue;
            }
            if (!file.hash().equals(meta.get("importFileHash"))) {
                throw new RuleService.ImportConflictException(
                        "This import id was already used for a different file. Choose the file again to start a new import.");
            }
            String stored = meta.get("importResult");
            if (stored != null) {
                return Optional.of(MAPPER.readValue(stored, RuleImportReport.class).asReplay());
            }
        }
        return Optional.empty();
    }

    private RuleImportReport report(Validation checked, boolean dryRun, String stamp, String changeSetId) {
        List<Imported> imported = new ArrayList<>();
        for (Accepted rule : checked.accepted()) {
            imported.add(new Imported(rule.id(), rule.name(), rule.row().row()));
        }
        return RuleImportReport.of(checked.read(), imported, checked.skipped(), dryRun, stamp, changeSetId);
    }

    private Validation validate(String projectId, boolean draft, String userId, RuleImportFile file) throws Exception {
        OWLOntology ontology = loader.load(projectId, draft, userId);
        Set<String> taken = new LinkedHashSet<>();
        ontology.axioms(AxiomType.SWRL_RULE).forEach(rule -> SwrlRuleKeys.name(rule)
                .ifPresent(name -> taken.add(name.trim().toLowerCase(Locale.ROOT))));
        Map<String, String> project = projectPrefixes(ontology);
        Map<String, Integer> seen = new HashMap<>();
        List<Accepted> accepted = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (RuleImportFile.Row row : file.rows()) {
            Skipped refusal = refusal(projectId, ontology, project, file.prefixes(), taken, seen, row, accepted);
            if (refusal != null) {
                skipped.add(refusal);
            }
        }
        checkSize(accepted);
        return new Validation(accepted, skipped, file.rows().size());
    }

    private Skipped refusal(String projectId, OWLOntology ontology, Map<String, String> project,
                            Map<String, String> declared, Set<String> taken, Map<String, Integer> seen,
                            RuleImportFile.Row row, List<Accepted> accepted) throws Exception {
        String name = row.name() == null ? "" : row.name().trim();
        if (row.problemKind() != null) {
            return skip(row, row.problemKind(), row.problem(), List.of());
        }
        if (name.isEmpty()) {
            return skip(row, RuleImportReport.INVALID, "A rule needs a name", List.of());
        }
        if (row.text() == null || row.text().isBlank()) {
            return skip(row, RuleImportReport.INVALID, "A rule needs rule text", List.of());
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (taken.contains(key)) {
            return skip(row, RuleImportReport.DUPLICATE, "A rule named \"" + name + "\" already exists", List.of());
        }
        if (seen.containsKey(key)) {
            return skip(row, RuleImportReport.DUPLICATE,
                    "The name \"" + name + "\" is already used on row " + seen.get(key) + " of this file", List.of());
        }
        Resolved resolved = resolve(row.text(), project, declared);
        if (resolved.conflict() != null) {
            return skip(row, RuleImportReport.PREFIX_CONFLICT, resolved.conflict(), List.of());
        }
        try {
            SWRLRule parsed = rules.parse(projectId, name, resolved.text());
            String id = UUID.randomUUID().toString();
            SWRLRule axiom = RuleService.annotated(parsed, id, name, RuleService.clean(row.comment()),
                    RuleService.clean(row.category()), row.enabled() == null || row.enabled(), Set.of());
            seen.put(key, row.row());
            accepted.add(new Accepted(row, id, name, axiom));
            return null;
        } catch (RuleService.InvalidRuleException e) {
            return classify(row, e, ontology);
        }
    }

    private static Skipped skip(RuleImportFile.Row row, String kind, String reason, List<String> suggestions) {
        return new Skipped(row.sheet(), row.row(), row.name() == null ? "" : row.name(), reason, kind, suggestions);
    }

    private static Skipped classify(RuleImportFile.Row row, RuleService.InvalidRuleException e, OWLOntology ontology) {
        Matcher unknown = UNKNOWN.matcher(e.getMessage() == null ? "" : e.getMessage());
        if (!unknown.find()) {
            return skip(row, RuleImportReport.INVALID, e.getMessage(), e.suggestions());
        }
        String entity = unknown.group(1);
        List<String> namespaces = new ArrayList<>();
        for (OWLEntity candidate : (Iterable<OWLEntity>) ontology.signature()::iterator) {
            String iri = candidate.getIRI().toString();
            int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/')) + 1;
            if (iri.substring(cut).equals(entity) && !namespaces.contains(iri.substring(0, cut))) {
                namespaces.add(iri.substring(0, cut));
            }
        }
        if (namespaces.size() > 1) {
            return skip(row, RuleImportReport.AMBIGUOUS_ENTITY, "\"" + entity + "\" exists in more than one namespace ("
                    + String.join(", ", namespaces) + "). Write it with a prefix or its full IRI in <...>", List.of());
        }
        List<String> hints = e.suggestions().stream().filter(text -> text.startsWith("Did you mean")).toList();
        return skip(row, RuleImportReport.UNKNOWN_ENTITY, "\"" + entity + "\" is not in the ontology", hints);
    }

    private void checkSize(List<Accepted> accepted) throws Exception {
        if (accepted.isEmpty()) {
            return;
        }
        Set<SWRLRule> axioms = new LinkedHashSet<>();
        accepted.forEach(rule -> axioms.add(rule.axiom()));
        long triples = OwlAxiomSparqlWriter.toInsertData(axioms).lines().filter(line -> line.startsWith("  ")).count();
        int limit = Limit.MAX_DELTA_TRIPLES.value();
        if (triples > limit) {
            throw new IllegalArgumentException("These " + accepted.size() + " rules are too large to import as one step that can be undone. "
                    + "Split the file into smaller files (roughly " + Math.max(1, accepted.size() * limit / (int) triples / 2)
                    + " rules each) and import them one by one");
        }
    }

    private RuleImportReport commit(String projectId, boolean draft, String userId, String username,
                                    RuleImportFile file, String importId, Validation checked, ChangeOrigin origin,
                                    Supplier<String> permission) throws Exception {
        refuseIfForbidden(permission);
        Optional<RuleImportReport> replay = replay(projectId, importId, file);
        if (replay.isPresent()) {
            return replay.get();
        }
        OWLOntology fresh = loader.load(projectId, draft, userId);
        Set<String> taken = new LinkedHashSet<>();
        fresh.axioms(AxiomType.SWRL_RULE).forEach(rule -> SwrlRuleKeys.name(rule)
                .ifPresent(name -> taken.add(name.trim().toLowerCase(Locale.ROOT))));
        List<Accepted> committed = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>(checked.skipped());
        for (Accepted rule : checked.accepted()) {
            if (taken.contains(rule.name().toLowerCase(Locale.ROOT))) {
                skipped.add(skip(rule.row(), RuleImportReport.DUPLICATE,
                        "A rule named \"" + rule.name() + "\" was added while the import was running", List.of()));
            } else {
                committed.add(rule);
            }
        }
        skipped.sort(java.util.Comparator.comparingInt(Skipped::row));
        Validation outcome = new Validation(committed, skipped, checked.read());
        if (committed.isEmpty()) {
            return report(outcome, false, null, null);
        }
        checkSize(committed);
        String changeSetId = origin.changeSetId();
        RuleImportReport result = report(outcome, false, null, changeSetId);
        Set<SWRLRule> axioms = new LinkedHashSet<>();
        committed.forEach(rule -> axioms.add(rule.axiom()));
        String sparql = OwlAxiomSparqlWriter.toInsertData(axioms);
        String summary = "Imported " + committed.size() + (committed.size() == 1 ? " rule" : " rules") + " from "
                + file.fileName();
        ChangeOrigin noted = origin.withRule(new ChangeOrigin.RuleNote(summary, ChangeOrigin.RuleNote.IMPORTED, summary))
                .withBatch(batch(importId, file, committed, result));
        write(projectId, draft, userId, username, sparql, noted, committed);
        return result;
    }

    private Map<String, String> batch(String importId, RuleImportFile file, List<Accepted> committed,
                                      RuleImportReport result) throws Exception {
        List<Version> versions = new ArrayList<>();
        for (Accepted rule : committed) {
            versions.add(new Version(rule.id(), rule.name(), contentHash(rule.axiom())));
        }
        Map<String, String> batch = new LinkedHashMap<>();
        batch.put("importId", importId);
        batch.put("importFileHash", file.hash());
        batch.put("importFile", file.fileName());
        batch.put("importRules", MAPPER.writeValueAsString(versions));
        batch.put("importResult", MAPPER.writeValueAsString(result));
        return batch;
    }

    private void write(String projectId, boolean draft, String userId, String username, String sparql,
                       ChangeOrigin origin, List<Accepted> committed) {
        org.eclipse.rdf4j.model.Model before = support.snapshot(projectId, draft, userId);
        RuntimeException failure = null;
        try {
            applier.apply(projectId, sparql, draft, userId, username, origin);
        } catch (RuntimeException e) {
            failure = e;
        }
        if (support.recorded(projectId, origin.changeSetId())) {
            if (failure != null) {
                log.warn("[RULES] The import was saved but reported {}", failure.getMessage());
            }
            return;
        }
        log.error("[RULES] The import of {} rules was not recorded in the history, putting the graph back: {}",
                committed.size(), failure == null ? "no history entry" : failure.getMessage());
        if (before != null) {
            support.restore(projectId, draft, userId, before);
        }
        throw new RuleService.ImportFailedException("The rules could not be imported, so nothing was changed. Try again");
    }

    private Stamp stampBody(String projectId, boolean draft, String userId, RuleImportFile file, Validation checked) {
        StringBuilder outcome = new StringBuilder();
        checked.accepted().forEach(rule -> outcome.append("ok:").append(rule.row().row()).append(';'));
        checked.skipped().forEach(item -> outcome.append(item.kind()).append(':').append(item.row()).append(';'));
        int duplicates = (int) checked.skipped().stream().filter(item -> RuleImportReport.DUPLICATE.equals(item.kind())).count();
        return new Stamp(support.stamp(projectId, draft, userId), file.hash(),
                RuleSpreadsheet.sha256(outcome.toString().getBytes(StandardCharsets.UTF_8)), checked.accepted().size(),
                duplicates, checked.skipped().size() - duplicates);
    }

    private String stampOf(String projectId, boolean draft, String userId, RuleImportFile file, Validation checked)
            throws Exception {
        return MAPPER.writeValueAsString(stampBody(projectId, draft, userId, file, checked));
    }

    private void guardStale(String projectId, boolean draft, String userId, RuleImportFile file, Validation checked,
                            String previewStamp) throws Exception {
        Stamp expected;
        try {
            expected = MAPPER.readValue(previewStamp, Stamp.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("The preview is not valid any more. Choose the file again");
        }
        if (!file.hash().equals(expected.file())) {
            throw new IllegalArgumentException("This is not the file that was previewed. Choose the file again");
        }
        Stamp now = stampBody(projectId, draft, userId, file, checked);
        if (now.outcome().equals(expected.outcome())) {
            return;
        }
        String reason = "The result changed since the preview. It would now import " + now.imported() + " (was "
                + expected.imported() + "), with " + now.duplicates() + " duplicates (was " + expected.duplicates()
                + ") and " + now.invalid() + " invalid (was " + expected.invalid() + "). Review the list and import again";
        RuleImportReport fresh = report(checked, true, stampOf(projectId, draft, userId, file, checked), null);
        throw new RuleService.ImportStaleException(fresh.asStale(reason));
    }

    static String contentHash(SWRLRule rule) {
        List<String> body = rule.body().map(Object::toString).sorted().toList();
        List<String> head = rule.head().map(Object::toString).sorted().toList();
        String text = String.join("\u0001", SwrlRuleKeys.name(rule).orElse(""),
                String.valueOf(RuleService.commentOf(rule)), String.valueOf(RuleService.annotationText(rule, DisabledRules.CATEGORY)),
                String.valueOf(DisabledRules.isDisabled(rule)), String.join(",", body), String.join(",", head));
        return RuleSpreadsheet.sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    Optional<String> blocker(String projectId, boolean draft, String userId, String importedRules) throws Exception {
        List<Version> expected = MAPPER.readValue(importedRules, new TypeReference<List<Version>>() {
        });
        OWLOntology ontology = loader.load(projectId, draft, userId);
        Map<String, SWRLRule> current = new HashMap<>();
        ontology.axioms(AxiomType.SWRL_RULE).forEach(rule -> SwrlRuleKeys.ruleId(rule).ifPresent(id -> current.put(id, rule)));
        for (Version version : expected) {
            SWRLRule rule = current.get(version.id());
            if (rule == null) {
                return Optional.of(blocked(version.name(), "was deleted", expected.size()));
            }
            if (!version.hash().equals(contentHash(rule))) {
                String now = SwrlRuleKeys.name(rule).orElse(version.name());
                return Optional.of(blocked(version.name(), now.equals(version.name()) ? "was edited" : "was renamed to \"" + now + "\"",
                        expected.size()));
            }
        }
        return Optional.empty();
    }

    private static String blocked(String name, String what, int total) {
        return "Rule \"" + name + "\" " + what + " after the import, so the import cannot be undone as a whole. "
                + "None of the " + total + " imported rules were removed";
    }

    record Resolved(String text, String conflict) {
    }

    static Map<String, String> projectPrefixes(OWLOntology ontology) {
        Map<String, String> prefixes = new LinkedHashMap<>();
        OWLDocumentFormat format = ontology.getOWLOntologyManager().getOntologyFormat(ontology);
        if (format instanceof PrefixDocumentFormat prefixFormat) {
            prefixFormat.getPrefixName2PrefixMap().forEach((name, namespace) -> {
                String key = name.endsWith(":") ? name.substring(0, name.length() - 1) : name;
                boolean usable = !namespace.startsWith("string:") && !(key.isEmpty() && namespace.startsWith("http://www.w3.org/"));
                if (usable) {
                    prefixes.put(key, namespace);
                }
            });
        }
        if (!prefixes.containsKey("")) {
            ontology.getOntologyID().getOntologyIRI().ifPresent(iri -> {
                String text = iri.toString();
                prefixes.put("", text.endsWith("#") || text.endsWith("/") ? text : text + "#");
            });
        }
        return prefixes;
    }

    private static String masked(String text) {
        StringBuilder out = new StringBuilder(text);
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            int end = c == '<' ? out.indexOf(">", i + 1) : c == '"' ? out.indexOf("\"", i + 1) : -1;
            if (end > 0) {
                for (int k = i; k <= end; k++) {
                    out.setCharAt(k, ' ');
                }
                i = end;
            }
        }
        return out.toString();
    }

    static Resolved resolve(String text, Map<String, String> project, Map<String, String> declared) {
        if (declared.isEmpty()) {
            return new Resolved(text, null);
        }
        String shown = masked(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        Matcher matcher = PREFIXED.matcher(shown);
        while (matcher.find()) {
            String prefix = matcher.group(1);
            String fileNamespace = declared.get(prefix);
            if (fileNamespace == null) {
                continue;
            }
            String projectNamespace = project.get(prefix);
            if (projectNamespace != null && !projectNamespace.equals(fileNamespace)) {
                return new Resolved(text, "The prefix \"" + prefix + "\" means <" + fileNamespace
                        + "> in this file but <" + projectNamespace + "> in this project");
            }
            if (projectNamespace == null) {
                out.append(text, last, matcher.start()).append('<').append(fileNamespace).append(matcher.group(2)).append('>');
                last = matcher.end();
            }
        }
        out.append(text.substring(last));
        String defaultFile = declared.get("");
        String defaultProject = project.get("");
        if (defaultFile != null && defaultProject != null && !defaultFile.equals(defaultProject) && usesBareNames(shown)) {
            return new Resolved(text, "The default namespace is <" + defaultFile + "> in this file but <" + defaultProject
                    + "> in this project, and this rule uses names without a prefix");
        }
        return new Resolved(out.toString(), null);
    }

    private static boolean usesBareNames(String shown) {
        Matcher matcher = BARE.matcher(shown);
        while (matcher.find()) {
            if (!BUILT_IN_WORDS.contains(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    Export export(String projectId, boolean draft, String userId) throws Exception {
        List<RuleSpreadsheet.ExportRule> local = new ArrayList<>();
        int leftOut = 0;
        for (Map<String, Object> item : client.listEmbedded(projectId, draft, userId)) {
            Object text = item.get("ruleText");
            boolean imported = item.get("source") != null || Boolean.TRUE.equals(item.get("readOnly"));
            if (imported || text == null || String.valueOf(text).isBlank()) {
                leftOut++;
                continue;
            }
            local.add(new RuleSpreadsheet.ExportRule(String.valueOf(item.get("ruleName")), String.valueOf(text),
                    item.get("comment") == null ? "" : String.valueOf(item.get("comment")),
                    item.get("category") == null ? "" : String.valueOf(item.get("category")),
                    !Boolean.FALSE.equals(item.get("enabled"))));
        }
        Map<String, String> project = projectPrefixes(loader.load(projectId, draft, userId));
        Map<String, String> used = new LinkedHashMap<>();
        if (project.containsKey("")) {
            used.put("", project.get(""));
        }
        for (RuleSpreadsheet.ExportRule rule : local) {
            Matcher matcher = PREFIXED.matcher(masked(rule.text()));
            while (matcher.find()) {
                String namespace = project.get(matcher.group(1));
                if (namespace != null) {
                    used.putIfAbsent(matcher.group(1), namespace);
                }
            }
        }
        return new Export(local, used, leftOut);
    }
}
