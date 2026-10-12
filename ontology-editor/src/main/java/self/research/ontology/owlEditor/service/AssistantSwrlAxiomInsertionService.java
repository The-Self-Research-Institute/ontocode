package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditOperation;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.InferredAxiomInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Service
public class AssistantSwrlAxiomInsertionService {

    public static final String ADD_INFERRED_AXIOMS = "add_inferred_axioms";
    public static final String SUPPORTED_FORMATS_TEXT = "turtle and ntriples";

    private static final Set<String> TURTLE_FORMATS = Set.of("turtle", "ttl");
    private static final Set<String> NTRIPLES_FORMATS = Set.of("ntriples", "nt");

    private final StorageManager storageManager;
    private final AssistantGraphIdentifierLookup graphLookup;

    public AssistantSwrlAxiomInsertionService(StorageManager storageManager, AssistantGraphIdentifierLookup graphLookup) {
        this.storageManager = storageManager;
        this.graphLookup = graphLookup;
    }

    public record DerivedEdit(long line, String originalText, String newText) {}

    public record InsertionDerivation(boolean ok, String detail, List<DerivedEdit> edits, int axiomCount) {
        static InsertionDerivation refused(String detail) {
            return new InsertionDerivation(false, detail, List.of(), 0);
        }
    }

    public static boolean isSupportedFormat(String targetPath) {
        String lower = targetPath == null ? "" : targetPath.toLowerCase(Locale.ROOT);
        return TURTLE_FORMATS.contains(lower) || NTRIPLES_FORMATS.contains(lower);
    }

    public InsertionDerivation derive(String projectId, EditOperation operation, int maxAxioms) {
        String operationProblem = operationProblem(operation);
        if (operationProblem != null) {
            return InsertionDerivation.refused(operationProblem);
        }
        String targetPath = operation.targetPath();
        List<InferredAxiomInput> axioms = operation.axioms();
        if (axioms.size() > maxAxioms) {
            return InsertionDerivation.refused("Too many inferred axioms (" + axioms.size()
                    + ") in one request; at most " + maxAxioms + " can be added at a time.");
        }

        List<String> referencedIris = axioms.stream()
                .flatMap(a -> Stream.of(a.subjectIri(), a.predicateIri(), a.objectIri()))
                .filter(iri -> iri != null && !iri.isBlank())
                .distinct()
                .toList();
        Set<String> existing;
        try {
            existing = graphLookup.existing(projectId, referencedIris);
        } catch (Exception e) {
            return InsertionDerivation.refused("Could not confirm the inferred axioms' subjects/objects exist in "
                    + "the graph (" + e.getMessage() + "), so none were added.");
        }

        List<String> lines = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (InferredAxiomInput axiom : axioms) {
            String line = renderAxiomLine(axiom, existing, skipped);
            if (line != null) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return InsertionDerivation.refused("None of the " + axioms.size() + " inferred axiom(s) could be "
                    + "rendered as new statements (" + String.join("; ", skipped) + ").");
        }

        long totalLines;
        try {
            totalLines = storageManager.readCodeViewPage(projectId, targetPath, 0, 0).totalLines();
        } catch (Exception e) {
            return InsertionDerivation.refused("Could not read the " + targetPath + " document to append the "
                    + "inferred axioms (" + e.getMessage() + ").");
        }

        String newText = String.join("\n", lines) + "\n";
        DerivedEdit edit = new DerivedEdit(totalLines, "", newText);
        String detail = "Adding " + lines.size() + " inferred axiom" + (lines.size() == 1 ? "" : "s")
                + " as new asserted statement" + (lines.size() == 1 ? "" : "s")
                + (skipped.isEmpty() ? "." : " (" + skipped.size() + " skipped: " + String.join("; ", skipped) + ").");
        return new InsertionDerivation(true, detail, List.of(edit), lines.size());
    }

    private String renderAxiomLine(InferredAxiomInput axiom, Set<String> existingIris, List<String> skipped) {
        String subject = axiom.subjectIri();
        String predicate = axiom.predicateIri();
        if (subject == null || predicate == null) {
            skipped.add((axiom.axiomType() == null ? "axiom" : axiom.axiomType()) + ": not structurally supported yet");
            return null;
        }
        if (!existingIris.contains(subject)) {
            skipped.add("<" + subject + "> does not exist in the graph");
            return null;
        }
        if (!AssistantGraphIdentifierLookup.isBuiltIn(predicate) && !existingIris.contains(predicate)) {
            skipped.add("<" + predicate + "> does not exist in the graph");
            return null;
        }
        String object = axiom.objectIri();
        if (object != null && !object.isBlank()) {
            if (!existingIris.contains(object)) {
                skipped.add("<" + object + "> does not exist in the graph");
                return null;
            }
            return "<" + subject + "> <" + predicate + "> <" + object + "> .";
        }
        String literal = axiom.objectLiteral();
        if (literal == null) {
            skipped.add((axiom.axiomType() == null ? "axiom" : axiom.axiomType()) + ": no object or literal value");
            return null;
        }
        return "<" + subject + "> <" + predicate + "> " + renderLiteral(literal, axiom.literalDatatypeIri(),
                axiom.literalLangTag()) + " .";
    }

    private String renderLiteral(String literal, String datatypeIri, String langTag) {
        String escaped = literal.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
        if (langTag != null && !langTag.isBlank()) {
            return "\"" + escaped + "\"@" + langTag;
        }
        if (datatypeIri != null && !datatypeIri.isBlank()) {
            return "\"" + escaped + "\"^^<" + datatypeIri + ">";
        }
        return "\"" + escaped + "\"";
    }

    private static String operationProblem(EditOperation operation) {
        if (operation == null || !ADD_INFERRED_AXIOMS.equals(operation.type())) {
            return "Unsupported operation type '" + (operation == null ? null : operation.type())
                    + "'. Expected " + ADD_INFERRED_AXIOMS + ".";
        }
        String targetPath = operation.targetPath();
        if (targetPath == null || targetPath.isBlank()) {
            return "The " + ADD_INFERRED_AXIOMS + " operation has no targetPath.";
        }
        if (!isSupportedFormat(targetPath)) {
            return "Adding inferred axioms is only supported for " + SUPPORTED_FORMATS_TEXT
                    + " documents; switch the Code View to one of these formats first.";
        }
        List<InferredAxiomInput> axioms = operation.axioms();
        if (axioms == null || axioms.isEmpty()) {
            return "No inferred axioms were supplied to add.";
        }
        return null;
    }
}
