package self.research.ontology.owlEditor.service;

import java.util.List;
import java.util.Optional;

public final class MutationIriGuard {

    private static final String FORBIDDEN = "<>\"{}|\\^`";

    private MutationIriGuard() {
    }

    public static Optional<String> firstProblem(List<OntologyMutationService.MutationOp> ops) {
        if (ops == null) {
            return Optional.empty();
        }
        for (OntologyMutationService.MutationOp op : ops) {
            if (op == null || op.type() == null || !op.type().startsWith("create") || op.iri() == null) {
                continue;
            }
            Optional<String> problem = problem(op.iri());
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    static Optional<String> problem(String iri) {
        if (iri.indexOf('#') != iri.lastIndexOf('#')) {
            return Optional.of("The IRI \"" + iri + "\" contains more than one '#'. Use a single '#' between the ontology IRI and the name.");
        }
        for (char c : iri.toCharArray()) {
            if (c <= ' ' || FORBIDDEN.indexOf(c) >= 0) {
                return Optional.of("The IRI \"" + iri + "\" contains a character that is not allowed in an IRI ("
                        + (c <= ' ' ? "space or control character" : "'" + c + "'") + ").");
            }
        }
        return Optional.empty();
    }
}
