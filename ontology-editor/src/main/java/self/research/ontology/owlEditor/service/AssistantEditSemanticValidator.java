package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.CheckResult;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static self.research.ontology.owlEditor.service.AssistantGraphIdentifierLookup.OWL_ANNOTATION_PROPERTY;
import static self.research.ontology.owlEditor.service.AssistantGraphIdentifierLookup.OWL_CLASS;
import static self.research.ontology.owlEditor.service.AssistantGraphIdentifierLookup.OWL_DATATYPE_PROPERTY;
import static self.research.ontology.owlEditor.service.AssistantGraphIdentifierLookup.OWL_NAMED_INDIVIDUAL;
import static self.research.ontology.owlEditor.service.AssistantGraphIdentifierLookup.OWL_OBJECT_PROPERTY;

@Slf4j
@Service
public class AssistantEditSemanticValidator {

    public static final String REFERENCES_RESOLVE = "references_resolve";
    public static final String NO_CONFLICTING_DECLARATION = "no_conflicting_declaration";
    public static final String NOT_APPLICABLE = "not applicable for this format";
    public static final int MAX_IDENTIFIERS_PER_GROUP = 50;

    private final StorageManager storageManager;
    private final AssistantGraphIdentifierLookup graphLookup;

    public AssistantEditSemanticValidator(StorageManager storageManager, AssistantGraphIdentifierLookup graphLookup) {
        this.storageManager = storageManager;
        this.graphLookup = graphLookup;
    }

    public record SemanticEdit(String originalText, String newText) {}

    private static final class Extraction {
        final Set<String> terms = new LinkedHashSet<>();
        final Set<String> unresolved = new LinkedHashSet<>();
        final Set<String> subjects = new LinkedHashSet<>();
        final Map<String, Set<String>> declared = new LinkedHashMap<>();

        void declare(String subject, String type) {
            if (AssistantGraphIdentifierLookup.DECLARATION_KINDS.contains(type)) {
                declared.computeIfAbsent(subject, k -> new LinkedHashSet<>()).add(type);
            }
        }
    }

    private record Extracted(Extraction before, Extraction after) {}

    public static List<CheckResult> notApplicable() {
        return List.of(new CheckResult(REFERENCES_RESOLVE, true, NOT_APPLICABLE),
                new CheckResult(NO_CONFLICTING_DECLARATION, true, NOT_APPLICABLE));
    }

    public static List<CheckResult> skipped(String reason) {
        return List.of(new CheckResult(REFERENCES_RESOLVE, true, reason),
                new CheckResult(NO_CONFLICTING_DECLARATION, true, reason));
    }

    public List<CheckResult> check(String projectId, String targetPath, List<SemanticEdit> edits,
                                   Set<String> introducedByOperation) {
        if (!AssistantRenameService.isSupportedFormat(targetPath)) {
            return notApplicable();
        }
        Extracted extracted;
        try {
            Path file = storageManager.ensureCodeViewFile(projectId, targetPath);
            extracted = AssistantRenameService.isRdfXml(targetPath)
                    ? extractRdfXml(file, edits)
                    : extractTurtle(file, edits);
        } catch (Exception e) {
            log.warn("[Assistant] Semantic checks could not read project {} targetPath {}: {}",
                    projectId, targetPath, e.getMessage());
            String detail = "Could not read the document to check this group (" + e.getMessage() + ").";
            return List.of(new CheckResult(REFERENCES_RESOLVE, false, detail),
                    new CheckResult(NO_CONFLICTING_DECLARATION, false, detail));
        }
        List<CheckResult> results = new ArrayList<>();
        results.add(checkReferences(projectId, extracted, introducedByOperation));
        results.add(checkDeclarations(projectId, extracted));
        return results;
    }

    private CheckResult checkReferences(String projectId, Extracted extracted, Set<String> introducedByOperation) {
        Extraction after = extracted.after();
        Extraction before = extracted.before();
        List<String> unresolved = after.unresolved.stream().filter(t -> !before.unresolved.contains(t)).toList();
        List<String> candidates = after.terms.stream()
                .filter(iri -> !before.terms.contains(iri))
                .filter(iri -> !AssistantGraphIdentifierLookup.isBuiltIn(iri))
                .filter(iri -> !after.subjects.contains(iri))
                .filter(iri -> !introducedByOperation.contains(iri))
                .toList();
        List<String> checked = candidates.subList(0, Math.min(candidates.size(), MAX_IDENTIFIERS_PER_GROUP));
        int notChecked = candidates.size() - checked.size();
        List<String> missing = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        if (!checked.isEmpty()) {
            List<String> lookup = new ArrayList<>();
            for (String iri : checked) {
                if (AssistantGraphIdentifierLookup.isSafeIri(iri)) {
                    lookup.add(iri);
                } else {
                    invalid.add(iri);
                }
            }
            try {
                Set<String> existing = lookup.isEmpty() ? Set.of() : graphLookup.existing(projectId, lookup);
                for (String iri : lookup) {
                    if (!existing.contains(iri)) {
                        missing.add(iri);
                    }
                }
            } catch (Exception e) {
                return new CheckResult(REFERENCES_RESOLVE, false,
                        "Could not look up this group's new identifiers in the graph (" + e.getMessage() + ").");
            }
        }
        List<String> problems = new ArrayList<>();
        if (!missing.isEmpty()) {
            problems.add("these identifiers are not in the graph and are not declared in this group, which usually "
                    + "means a typo: " + angle(missing));
        }
        if (!invalid.isEmpty()) {
            problems.add("these are not valid IRIs: " + angle(invalid));
        }
        if (!unresolved.isEmpty()) {
            problems.add("these can't be resolved to full IRIs (undeclared prefix or relative IRI): "
                    + String.join(", ", unresolved));
        }
        String overflow = notChecked > 0
                ? " " + notChecked + " more new identifier" + (notChecked == 1 ? " was" : "s were")
                + " not checked (limit " + MAX_IDENTIFIERS_PER_GROUP + " per group)."
                : "";
        if (!problems.isEmpty()) {
            return new CheckResult(REFERENCES_RESOLVE, false, capitalize(String.join("; ", problems)) + "." + overflow);
        }
        if (candidates.isEmpty()) {
            return new CheckResult(REFERENCES_RESOLVE, true,
                    "This group introduces no identifiers that need to exist already.");
        }
        return new CheckResult(REFERENCES_RESOLVE, true, "All " + checked.size() + " new identifier"
                + (checked.size() == 1 ? "" : "s") + " referenced by this group already exist in the graph." + overflow);
    }

    private CheckResult checkDeclarations(String projectId, Extracted extracted) {
        Map<String, Set<String>> declared = extracted.after().declared;
        if (declared.isEmpty()) {
            return new CheckResult(NO_CONFLICTING_DECLARATION, true, "This group declares no OWL entities.");
        }
        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : declared.entrySet()) {
            List<String> kinds = new ArrayList<>(entry.getValue());
            for (int i = 0; i < kinds.size(); i++) {
                for (int j = i + 1; j < kinds.size(); j++) {
                    if (incompatible(kinds.get(i), kinds.get(j))) {
                        conflicts.add("<" + entry.getKey() + "> is declared in this group as both "
                                + shortKind(kinds.get(i)) + " and " + shortKind(kinds.get(j)));
                    }
                }
            }
        }
        List<String> iris = new ArrayList<>(declared.keySet());
        List<String> checked = iris.subList(0, Math.min(iris.size(), MAX_IDENTIFIERS_PER_GROUP));
        int notChecked = iris.size() - checked.size();
        Map<String, Set<String>> graphKinds;
        try {
            graphKinds = graphLookup.declaredKinds(projectId, checked);
        } catch (Exception e) {
            return new CheckResult(NO_CONFLICTING_DECLARATION, false,
                    "Could not look up existing declarations in the graph (" + e.getMessage() + ").");
        }
        Map<String, Set<String>> removed = removedDeclarations(extracted);
        for (String iri : checked) {
            Set<String> existing = new LinkedHashSet<>(graphKinds.getOrDefault(iri, Set.of()));
            existing.removeAll(removed.getOrDefault(iri, Set.of()));
            for (String kind : declared.get(iri)) {
                for (String other : existing) {
                    if (incompatible(kind, other)) {
                        conflicts.add("<" + iri + "> is declared here as " + shortKind(kind)
                                + " but already exists in the graph as " + shortKind(other));
                    }
                }
            }
        }
        String overflow = notChecked > 0
                ? " " + notChecked + " more declared identifier" + (notChecked == 1 ? " was" : "s were")
                + " not checked (limit " + MAX_IDENTIFIERS_PER_GROUP + " per group)."
                : "";
        if (!conflicts.isEmpty()) {
            return new CheckResult(NO_CONFLICTING_DECLARATION, false, String.join("; ", conflicts)
                    + ". Class-and-individual punning is allowed, but a class can't also be a property and the "
                    + "object, datatype and annotation property kinds can't be mixed." + overflow);
        }
        return new CheckResult(NO_CONFLICTING_DECLARATION, true, "None of the " + checked.size()
                + " declared identifier" + (checked.size() == 1 ? "" : "s")
                + " conflicts with an existing declaration." + overflow);
    }

    private Map<String, Set<String>> removedDeclarations(Extracted extracted) {
        Map<String, Set<String>> removed = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : extracted.before().declared.entrySet()) {
            Set<String> stillDeclared = extracted.after().declared.getOrDefault(entry.getKey(), Set.of());
            for (String kind : entry.getValue()) {
                if (!stillDeclared.contains(kind)) {
                    removed.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>()).add(kind);
                }
            }
        }
        return removed;
    }

    static boolean incompatible(String a, String b) {
        if (a.equals(b) || a.equals(OWL_NAMED_INDIVIDUAL) || b.equals(OWL_NAMED_INDIVIDUAL)) {
            return false;
        }
        return isPropertyKind(a) || isPropertyKind(b);
    }

    private static boolean isPropertyKind(String kind) {
        return kind.equals(OWL_OBJECT_PROPERTY) || kind.equals(OWL_DATATYPE_PROPERTY)
                || kind.equals(OWL_ANNOTATION_PROPERTY);
    }

    private static String shortKind(String kind) {
        return switch (kind) {
            case OWL_CLASS -> "owl:Class";
            case OWL_OBJECT_PROPERTY -> "owl:ObjectProperty";
            case OWL_DATATYPE_PROPERTY -> "owl:DatatypeProperty";
            case OWL_ANNOTATION_PROPERTY -> "owl:AnnotationProperty";
            case OWL_NAMED_INDIVIDUAL -> "owl:NamedIndividual";
            default -> "<" + kind + ">";
        };
    }

    private static String angle(List<String> iris) {
        return iris.stream().map(i -> "<" + i + ">").collect(Collectors.joining(", "));
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private Extracted extractTurtle(Path file, List<SemanticEdit> edits) throws Exception {
        Set<String> neededPrefixes = new LinkedHashSet<>();
        boolean needsBase = false;
        for (SemanticEdit edit : edits) {
            for (String text : new String[]{edit.originalText(), edit.newText()}) {
                if (text == null) {
                    continue;
                }
                TurtleLineScanner probe = new TurtleLineScanner();
                for (String line : text.split("\n", -1)) {
                    for (TurtleLineScanner.Token token : probe.scan(line)) {
                        if (token.isTerm() && token.unresolved()) {
                            if (token.kind() == TurtleLineScanner.Kind.PNAME) {
                                neededPrefixes.add(token.prefix());
                            } else {
                                needsBase = true;
                            }
                        }
                    }
                }
            }
        }
        Map<String, String> prefixes = new HashMap<>();
        String base = null;
        if (!neededPrefixes.isEmpty() || needsBase) {
            TurtleLineScanner documentScanner = new TurtleLineScanner();
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    documentScanner.scan(line);
                    for (String prefix : neededPrefixes) {
                        String namespace = documentScanner.prefixes().get(prefix);
                        if (namespace != null) {
                            prefixes.putIfAbsent(prefix, namespace);
                        }
                    }
                    if (base == null) {
                        base = documentScanner.base();
                    }
                    if (prefixes.keySet().containsAll(neededPrefixes) && (!needsBase || base != null)) {
                        break;
                    }
                }
            }
        }
        Extraction before = new Extraction();
        Extraction after = new Extraction();
        for (SemanticEdit edit : edits) {
            scanTurtleText(edit.originalText(), prefixes, base, before);
            scanTurtleText(edit.newText(), prefixes, base, after);
        }
        return new Extracted(before, after);
    }

    private void scanTurtleText(String text, Map<String, String> prefixes, String base, Extraction extraction) {
        if (text == null || text.isEmpty()) {
            return;
        }
        TurtleLineScanner scanner = new TurtleLineScanner(prefixes, base);
        TurtleStatementTracker tracker = new TurtleStatementTracker();
        for (String line : text.split("\n", -1)) {
            for (TurtleLineScanner.Token token : scanner.scan(line)) {
                if (token.isTerm()) {
                    if (token.unresolved()) {
                        extraction.unresolved.add(token.text());
                    } else {
                        extraction.terms.add(token.iri());
                    }
                }
                tracker.accept(token);
            }
        }
        extraction.subjects.addAll(tracker.subjects());
        tracker.declaredTypes().forEach((subject, types) -> types.forEach(type -> extraction.declare(subject, type)));
    }

    private Extracted extractRdfXml(Path file, List<SemanticEdit> edits) throws Exception {
        RdfXmlScanner header = new RdfXmlScanner(new RdfXmlScanner.Listener() {});
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            long lineNo = 0;
            while (!header.rootSeen() && (line = reader.readLine()) != null) {
                header.feed(lineNo++, line);
            }
        }
        Map<String, String> namespaces = header.rootNamespaces();
        String base = header.rootBase();
        Map<String, String> entities = new HashMap<>(header.entities());
        Extraction before = new Extraction();
        Extraction after = new Extraction();
        for (SemanticEdit edit : edits) {
            scanRdfXmlText(edit.originalText(), namespaces, base, entities, before);
            scanRdfXmlText(edit.newText(), namespaces, base, entities, after);
        }
        return new Extracted(before, after);
    }

    private void scanRdfXmlText(String text, Map<String, String> namespaces, String base, Map<String, String> entities,
                                Extraction extraction) {
        if (text == null || text.isEmpty()) {
            return;
        }
        RdfXmlScanner scanner = new RdfXmlScanner(namespaces, base, entities, new RdfXmlScanner.Listener() {
            @Override
            public void occurrence(RdfXmlScanner.Occurrence occurrence) {
                if (!occurrence.resolvable()) {
                    extraction.unresolved.add(occurrence.rawText());
                }
            }

            @Override
            public void reference(String iri) {
                extraction.terms.add(iri);
            }

            @Override
            public void subject(String iri) {
                extraction.subjects.add(iri);
                extraction.terms.add(iri);
            }

            @Override
            public void declared(String subject, String type) {
                extraction.declare(subject, type);
            }
        });
        long lineNo = 0;
        for (String line : text.split("\n", -1)) {
            scanner.feed(lineNo++, line);
        }
    }
}
