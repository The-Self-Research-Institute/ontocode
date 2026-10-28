package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.model.Statement;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.service.SparqlQueryScope;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InferencePerformanceTest {

    static String data(int individuals) {
        StringBuilder sb = new StringBuilder();
        sb.append(":knows a owl:ObjectProperty . :near a owl:ObjectProperty , owl:TransitiveProperty . :age a owl:DatatypeProperty . ");
        sb.append(":Base a owl:Class . ");
        for (int c = 0; c < 20; c++) {
            sb.append(":C").append(c).append(" a owl:Class ; rdfs:subClassOf ").append(c == 0 ? ":Base" : ":C" + (c - 1)).append(" . ");
        }
        for (int i = 0; i < individuals; i++) {
            sb.append(":i").append(i).append(" a owl:NamedIndividual , :C").append(i % 20).append(" ; :age ").append(i % 90)
                    .append(" ; :knows :i").append((i + 1) % individuals).append(" . ");
            if (i % 10 == 0 && i + 1 < individuals) {
                sb.append(":i").append(i).append(" :near :i").append(i + 1).append(" . ");
                sb.append(":i").append(i + 1).append(" :near :i").append((i + 2) % individuals).append(" . ");
            }
            if (i % 20 == 0 && i + 1 < individuals) {
                sb.append(":i").append(i).append(" owl:sameAs :i").append(i + 1).append(" . ");
            }
        }
        return sb.toString();
    }

    @Test
    void expansionAndTimingAtOneThousandIndividuals() {
        InferenceTestKit kit = new InferenceTestKit();
        SparqlQueryScope scope = InferenceFixtures.publicScope();
        List<Statement> statements = InferenceFixtures.turtle(data(1000), scope.graphs().get(0));
        kit.source.set(scope, statements);
        long t0 = System.nanoTime();
        InferenceService.Answer first = kit.service.query(scope, null, InferenceTestKit.P + "ASK { :i0 :knows :i2 }", true, 10);
        long computeMs = (System.nanoTime() - t0) / 1_000_000;
        long q0 = System.nanoTime();
        boolean viaSameAs = kit.askInferred(scope, null, ":i0 :knows :i2", false);
        boolean typeUnderBoth = kit.askInferred(scope, null, ":i1 a :C0 . :i1 a :Base", false);
        long queryMs = (System.nanoTime() - q0) / 1_000_000;
        @SuppressWarnings("unchecked")
        Map<String, Object> eq = (Map<String, Object>) first.inferred().get("equality");
        System.out.println("[PERF] asserted=" + first.inferred().get("assertedTripleCount") + " inferred="
                + first.inferred().get("inferredTripleCount") + " equality=" + eq + " computeMs=" + computeMs
                + " twoQueriesMs=" + queryMs);
        assertTrue(viaSameAs);
        assertTrue(typeUnderBoth);
        assertTrue(computeMs < InferenceProfile.TIME_LIMIT_MS);
        assertTrue((Long) first.inferred().get("inferredTripleCount") > 0);
    }
}
