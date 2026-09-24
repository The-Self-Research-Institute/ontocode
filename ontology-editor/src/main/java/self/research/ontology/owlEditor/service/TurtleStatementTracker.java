package self.research.ontology.owlEditor.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class TurtleStatementTracker {

    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private enum Stage { SUBJECT, PREDICATE, OBJECT, AFTER_OBJECT }

    private final Set<String> subjects = new LinkedHashSet<>();
    private final Map<String, Set<String>> declaredTypes = new LinkedHashMap<>();
    private final boolean recording;
    private Stage stage = Stage.SUBJECT;
    private int depth;
    private String subject;
    private String predicate;

    public TurtleStatementTracker() {
        this(true);
    }

    private TurtleStatementTracker(boolean recording) {
        this.recording = recording;
    }

    public static TurtleStatementTracker positionOnly() {
        return new TurtleStatementTracker(false);
    }

    public TurtleStatementTracker fork() {
        TurtleStatementTracker copy = new TurtleStatementTracker(true);
        copy.stage = stage;
        copy.depth = depth;
        copy.subject = subject;
        copy.predicate = predicate;
        return copy;
    }

    public Set<String> subjects() {
        return subjects;
    }

    public Map<String, Set<String>> declaredTypes() {
        return declaredTypes;
    }

    public void accept(TurtleLineScanner.Token token) {
        if (token.directive() || token.kind() == TurtleLineScanner.Kind.OTHER) {
            return;
        }
        String text = token.text();
        if (token.kind() == TurtleLineScanner.Kind.PUNCT) {
            if (text.equals("[") || text.equals("(") || text.equals("<<")) {
                if (depth == 0 && stage == Stage.SUBJECT) {
                    subject = null;
                }
                depth++;
                return;
            }
            if (text.equals("]") || text.equals(")") || text.equals(">>")) {
                if (depth > 0) {
                    depth--;
                    if (depth == 0) {
                        stage = stage == Stage.SUBJECT ? Stage.PREDICATE : Stage.AFTER_OBJECT;
                    }
                }
                return;
            }
            if (depth > 0) {
                return;
            }
            switch (text) {
                case "." -> {
                    stage = Stage.SUBJECT;
                    subject = null;
                    predicate = null;
                }
                case ";" -> {
                    stage = Stage.PREDICATE;
                    predicate = null;
                }
                case "," -> stage = Stage.OBJECT;
                default -> {
                }
            }
            return;
        }
        if (depth > 0) {
            return;
        }
        String value = token.isTerm() && !token.unresolved() ? token.iri() : null;
        switch (stage) {
            case SUBJECT -> {
                if (token.kind() == TurtleLineScanner.Kind.A) {
                    subject = null;
                    predicate = RDF_TYPE;
                    stage = Stage.OBJECT;
                    return;
                }
                subject = value;
                if (subject != null && recording) {
                    subjects.add(subject);
                }
                stage = Stage.PREDICATE;
            }
            case PREDICATE -> {
                predicate = token.kind() == TurtleLineScanner.Kind.A ? RDF_TYPE : value;
                stage = Stage.OBJECT;
            }
            case OBJECT -> {
                if (recording && RDF_TYPE.equals(predicate) && subject != null && value != null) {
                    declaredTypes.computeIfAbsent(subject, k -> new LinkedHashSet<>()).add(value);
                }
                stage = Stage.AFTER_OBJECT;
            }
            case AFTER_OBJECT -> {
            }
        }
    }
}
