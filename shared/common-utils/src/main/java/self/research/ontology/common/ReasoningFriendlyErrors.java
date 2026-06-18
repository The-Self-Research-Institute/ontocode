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

        if (lower.contains("inconsistent")) {
            return "This ontology has inconsistent data (for example, conflicting class definitions). "
                    + "Fix the issues in the editor, then try again.";
        }
        if (lower.contains("outofmemory") || lower.contains("out of memory")) {
            return "This task needs more memory than is available right now. "
                    + "Try again in a few minutes, or use a smaller ontology / simpler query.";
        }
        if (lower.contains("too large") || lower.contains("triples")) {
            return "This ontology is too large for in-memory reasoning. "
                    + "Try the SPARQL tab, or simplify what you are asking the reasoner to do.";
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
        return raw;
    }
}
