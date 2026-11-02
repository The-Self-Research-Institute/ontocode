package self.research.ontology.owlEditor.service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class MutationIriGuard {

    private static final String FORBIDDEN = "<>\"{}|\\^`";
    private static final Pattern LANGUAGE_TAG = Pattern.compile("[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*");
    private static final Pattern PREFIXED_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*:[A-Za-z0-9_.-]*");

    private MutationIriGuard() {
    }

    public static Optional<String> firstProblem(List<OntologyMutationService.MutationOp> ops) {
        if (ops == null) {
            return Optional.empty();
        }
        for (OntologyMutationService.MutationOp op : ops) {
            if (op == null) {
                continue;
            }
            if (op.type() != null && op.type().startsWith("create") && op.iri() != null) {
                Optional<String> problem = problem(op.iri());
                if (problem.isPresent()) {
                    return problem;
                }
            }
            for (String iri : new String[]{op.iri(), op.parent(), op.property(), op.target(), op.classIri(),
                    op.ancestorIri()}) {
                Optional<String> problem = forbiddenCharacter(iri);
                if (problem.isPresent()) {
                    return problem;
                }
            }
            if (op.language() != null && !op.language().isBlank() && !LANGUAGE_TAG.matcher(op.language().trim()).matches()) {
                return Optional.of("The language tag \"" + op.language() + "\" is not valid.");
            }
            String datatype = op.datatype();
            if (datatype != null && !datatype.isBlank()) {
                Optional<String> problem = datatype.startsWith("http")
                        ? forbiddenCharacter(datatype)
                        : PREFIXED_NAME.matcher(datatype).matches()
                                ? Optional.<String>empty()
                                : Optional.of("The datatype \"" + datatype + "\" is not valid.");
                if (problem.isPresent()) {
                    return problem;
                }
            }
        }
        return Optional.empty();
    }

    static Optional<String> problem(String iri) {
        if (iri.indexOf('#') != iri.lastIndexOf('#')) {
            return Optional.of("The IRI \"" + iri + "\" contains more than one '#'. Use a single '#' between the ontology IRI and the name.");
        }
        return forbiddenCharacter(iri);
    }

    private static Optional<String> forbiddenCharacter(String iri) {
        if (iri == null) {
            return Optional.empty();
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
