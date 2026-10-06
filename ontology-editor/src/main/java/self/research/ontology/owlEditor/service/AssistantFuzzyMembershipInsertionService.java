package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.EditOperation;
import self.research.ontology.owlEditor.dto.ProposeEditRequest.FuzzyMembershipInput;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Service
public class AssistantFuzzyMembershipInsertionService {

    public static final String ADD_FUZZY_MEMBERSHIP = "add_fuzzy_membership";
    public static final String SUPPORTED_FORMATS_TEXT = "turtle and ntriples";
    private static final String FUZZY_PREFIX = "http://fuzzy.org/ontology#";
    private static final String XSD_DOUBLE = "http://www.w3.org/2001/XMLSchema#double";

    private static final Set<String> TURTLE_FORMATS = Set.of("turtle", "ttl");
    private static final Set<String> NTRIPLES_FORMATS = Set.of("ntriples", "nt");

    private final StorageManager storageManager;
    private final AssistantGraphIdentifierLookup graphLookup;
    private final FuzzyMembershipQueryService membershipQueryService;

    public AssistantFuzzyMembershipInsertionService(StorageManager storageManager,
                                                      AssistantGraphIdentifierLookup graphLookup,
                                                      FuzzyMembershipQueryService membershipQueryService) {
        this.storageManager = storageManager;
        this.graphLookup = graphLookup;
        this.membershipQueryService = membershipQueryService;
    }

    public record DerivedEdit(long line, String originalText, String newText) {}

    public record InsertionDerivation(boolean ok, String detail, List<DerivedEdit> edits, int membershipCount) {
        static InsertionDerivation refused(String detail) {
            return new InsertionDerivation(false, detail, List.of(), 0);
        }
    }

    public static boolean isSupportedFormat(String targetPath) {
        String lower = targetPath == null ? "" : targetPath.toLowerCase(Locale.ROOT);
        return TURTLE_FORMATS.contains(lower) || NTRIPLES_FORMATS.contains(lower);
    }

    public InsertionDerivation derive(String projectId, EditOperation operation, int maxMemberships) {
        String operationProblem = operationProblem(operation);
        if (operationProblem != null) {
            return InsertionDerivation.refused(operationProblem);
        }
        String targetPath = operation.targetPath();
        List<FuzzyMembershipInput> memberships = operation.memberships();
        if (memberships.size() > maxMemberships) {
            return InsertionDerivation.refused("Too many memberships (" + memberships.size()
                    + ") in one request; at most " + maxMemberships + " can be added at a time.");
        }

        List<String> referencedIris = memberships.stream()
                .flatMap(m -> Stream.of(m.entityIri(), m.classIri()))
                .filter(iri -> iri != null && !iri.isBlank())
                .distinct()
                .toList();
        Set<String> existingIris;
        try {
            existingIris = graphLookup.existing(projectId, referencedIris);
        } catch (Exception e) {
            return InsertionDerivation.refused("Could not confirm the memberships' entities/classes exist in the "
                    + "graph (" + e.getMessage() + "), so none were added.");
        }

        List<String> blocks = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> seenInBatch = new java.util.HashSet<>();
        long nonce = System.nanoTime();
        int index = 0;
        for (FuzzyMembershipInput membership : memberships) {
            String block = renderMembershipBlock(projectId, membership, existingIris, seenInBatch, nonce, index, skipped);
            if (block != null) {
                blocks.add(block);
            }
            index++;
        }
        if (blocks.isEmpty()) {
            return InsertionDerivation.refused("None of the " + memberships.size() + " membership(s) could be "
                    + "added (" + String.join("; ", skipped) + ").");
        }

        long totalLines;
        try {
            totalLines = storageManager.readCodeViewPage(projectId, targetPath, 0, 0).totalLines();
        } catch (Exception e) {
            return InsertionDerivation.refused("Could not read the " + targetPath + " document to append the "
                    + "memberships (" + e.getMessage() + ").");
        }

        String newText = String.join("\n", blocks) + "\n";
        DerivedEdit edit = new DerivedEdit(totalLines, "", newText);
        String detail = "Adding " + blocks.size() + " fuzzy membership" + (blocks.size() == 1 ? "" : "s")
                + (skipped.isEmpty() ? "." : " (" + skipped.size() + " skipped: " + String.join("; ", skipped) + ").");
        return new InsertionDerivation(true, detail, List.of(edit), blocks.size());
    }

    private String renderMembershipBlock(String projectId, FuzzyMembershipInput membership, Set<String> existingIris,
                                          Set<String> seenInBatch, long nonce, int index, List<String> skipped) {
        String entityIri = membership.entityIri();
        String classIri = membership.classIri();
        Double degree = membership.degree();
        if (entityIri == null || classIri == null || degree == null) {
            skipped.add("membership #" + index + ": entityIri, classIri and degree are all required");
            return null;
        }
        if (degree < 0.0 || degree > 1.0) {
            skipped.add("<" + entityIri + "> / <" + classIri + ">: degree " + degree + " is not between 0.0 and 1.0");
            return null;
        }
        if (!existingIris.contains(entityIri)) {
            skipped.add("<" + entityIri + "> does not exist in the graph");
            return null;
        }
        if (!existingIris.contains(classIri)) {
            skipped.add("<" + classIri + "> does not exist in the graph");
            return null;
        }
        String pairKey = entityIri + " " + classIri;
        if (!seenInBatch.add(pairKey)) {
            skipped.add("<" + entityIri + "> / <" + classIri + ">: duplicate membership in this same request");
            return null;
        }
        if (alreadyHasMembership(projectId, entityIri, classIri)) {
            skipped.add("<" + entityIri + "> already has a membership in <" + classIri + "> — change it in the "
                    + "Fuzzy plugin's tab instead of adding a new one");
            return null;
        }

        String blankNode = "_:fm" + Long.toHexString(nonce) + "x" + index;
        return "<" + entityIri + "> <" + FUZZY_PREFIX + "hasMembership> " + blankNode + " .\n"
                + blankNode + " <" + FUZZY_PREFIX + "inClass> <" + classIri + "> .\n"
                + blankNode + " <" + FUZZY_PREFIX + "degree> \"" + degree + "\"^^<" + XSD_DOUBLE + "> .";
    }

    private boolean alreadyHasMembership(String projectId, String entityIri, String classIri) {
        try {
            for (Map<String, Object> existing : membershipQueryService.forIndividual(projectId, entityIri)) {
                if (classIri.equals(existing.get("classIri"))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("[Assistant] Could not check existing fuzzy memberships for {}: {}", entityIri, e.getMessage());
            return false;
        }
    }

    private static String operationProblem(EditOperation operation) {
        if (operation == null || !ADD_FUZZY_MEMBERSHIP.equals(operation.type())) {
            return "Unsupported operation type '" + (operation == null ? null : operation.type())
                    + "'. Expected " + ADD_FUZZY_MEMBERSHIP + ".";
        }
        String targetPath = operation.targetPath();
        if (targetPath == null || targetPath.isBlank()) {
            return "The " + ADD_FUZZY_MEMBERSHIP + " operation has no targetPath.";
        }
        if (!isSupportedFormat(targetPath)) {
            return "Adding fuzzy memberships is only supported for " + SUPPORTED_FORMATS_TEXT
                    + " documents; switch the Code View to one of these formats first.";
        }
        List<FuzzyMembershipInput> memberships = operation.memberships();
        if (memberships == null || memberships.isEmpty()) {
            return "No fuzzy memberships were supplied to add.";
        }
        return null;
    }
}
