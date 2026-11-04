package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class EqualityExpander {

    record Result(Set<Statement> statements, long triplesAdded, boolean incomplete, List<String> reasons) {
    }

    private final int maxTriples;
    private final int maxGroup;

    EqualityExpander(int maxTriples, int maxGroup) {
        this.maxTriples = maxTriples;
        this.maxGroup = maxGroup;
    }

    Result expand(ExtractedFacts in, List<String> reasonsOut) {
        Set<Statement> out = new LinkedHashSet<>();
        long[] added = {0};
        boolean[] capped = {false};
        Map<IRI, List<IRI>> names = in.namesOf;

        for (Statement st : in.facts) {
            IRI s = (IRI) st.getSubject();
            Value o = st.getObject();
            List<IRI> subjects = names.getOrDefault(s, List.of(s));
            List<? extends Value> objects = o instanceof IRI oi ? names.getOrDefault(oi, List.of(oi)) : List.of(o);
            for (IRI ss : subjects) {
                for (Value oo : objects) {
                    boolean original = ss.equals(s) && oo.equals(o);
                    if (original) {
                        out.add(st);
                    } else if (!add(out, RdfTerms.triple(ss, st.getPredicate(), oo), added, capped)) {
                        break;
                    }
                }
            }
        }
        for (List<IRI> group : new LinkedHashSet<>(names.values())) {
            for (IRI a : group) {
                for (IRI b : group) {
                    if (!a.equals(b)) {
                        add(out, RdfTerms.triple(a, RdfTerms.SAME_AS, b), added, capped);
                    }
                }
            }
        }
        for (IRI[] pair : in.differentPairs) {
            for (IRI a : names.getOrDefault(pair[0], List.of(pair[0]))) {
                for (IRI b : names.getOrDefault(pair[1], List.of(pair[1]))) {
                    if (!a.equals(b)) {
                        add(out, RdfTerms.triple(a, RdfTerms.DIFFERENT_FROM, b), added, capped);
                    }
                }
            }
        }
        boolean incomplete = capped[0] || !in.oversizeGroups.isEmpty();
        if (capped[0]) {
            reasonsOut.add("Equality expansion stopped after " + maxTriples
                    + " added triples; some facts are not repeated under every name of a sameAs group.");
        }
        if (!in.oversizeGroups.isEmpty()) {
            reasonsOut.add("sameAs groups larger than " + maxGroup
                    + " names were not expanded (facts kept under the first name only): "
                    + String.join(", ", in.oversizeGroups.subList(0, Math.min(5, in.oversizeGroups.size())))
                    + (in.oversizeGroups.size() > 5 ? " and " + (in.oversizeGroups.size() - 5) + " more" : ""));
        }
        return new Result(out, added[0], incomplete, reasonsOut);
    }

    private boolean add(Set<Statement> out, Statement st, long[] added, boolean[] capped) {
        if (capped[0]) {
            return false;
        }
        if (added[0] >= maxTriples) {
            capped[0] = true;
            return false;
        }
        if (out.add(st)) {
            added[0]++;
        }
        return true;
    }
}
