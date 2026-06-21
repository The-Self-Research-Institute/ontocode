package self.research.ontology.common;

import java.util.Locale;

/**
 * User-facing messages for DL Query and Reasoner job failures.
 */
public final class ReasoningFriendlyErrors {

    private ReasoningFriendlyErrors() {}

    public static String forUser(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Something went wrong. Please try again in a moment.";
        }
        String lower = raw.toLowerCase(Locale.ROOT);

        if (lower.contains("outofmemory") || lower.contains("out of memory")) {
            return "This task needs more memory than is available right now. "
                    + "Try again in a few minutes, or use a smaller ontology / simpler query.";
        }
        if (lower.contains("too large") || lower.contains("triples")) {
            return "This ontology is too large for in-memory reasoning. "
                    + "Try the SPARQL tab, or simplify what you are asking the reasoner to do.";
        }
        if (lower.contains("stackoverflowerror") || lower.contains("stack overflow")) {
            return "The ontology is too deeply nested for the reasoner to handle. "
                    + "Try simplifying the class hierarchy or use a different reasoner.";
        }
        if (lower.contains("nosuchmethoderror") || lower.contains("nosuchmethod")) {
            return "A reasoner component is incompatible with this ontology format. "
                    + "Try a different reasoner type.";
        }
        // Malformed / unparseable OWL — check before "inconsistent" because some parsers
        // say "inconsistent" when they actually mean the file is structurally broken.
        if (lower.contains("malformed") || lower.contains("rdf:first")
                || lower.contains("ill-formed") || lower.contains("unexpected token")
                || lower.contains("unexpected end") || lower.contains("parse exception")) {
            return "The ontology file contains malformed RDF or OWL syntax. "
                    + "Open the file in the editor and check for structural issues.";
        }
        if (lower.contains("datatype") && (lower.contains("invalid") || lower.contains("facet")
                || lower.contains("violation") || lower.contains("error"))) {
            return "The ontology contains an invalid data type or facet restriction. "
                    + "Check your data property ranges in the editor.";
        }
        if (lower.contains("no dl reasoner") || lower.contains("reasoner available")
                || lower.contains("no reasoner")) {
            return "Reasoning is not available on this server right now. Please contact support.";
        }
        if (lower.contains("failed to parse") || lower.contains("manchester")) {
            return raw;
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "This task took too long and was stopped. Try again with a simpler request.";
        }
        if (lower.contains("not found") && lower.contains("ontology")) {
            return "We could not find this project's ontology. Open the project and try again.";
        }
        if (lower.contains("connection") || lower.contains("refused") || lower.contains("unavailable")) {
            return "The reasoning service is temporarily unavailable. Please try again in a minute.";
        }
        if (lower.contains("unsupported") || lower.contains("not supported")) {
            return "This reasoner cannot handle that request for this ontology. Try a different reasoner type.";
        }
        // True DL inconsistency — the ontology is logically contradictory.
        if (lower.contains("inconsistent")) {
            return "The ontology is logically inconsistent (a class has conflicting definitions "
                    + "or an individual belongs to disjoint classes). Fix the issues in the editor, then try again.";
        }
        return raw;
    }
}
