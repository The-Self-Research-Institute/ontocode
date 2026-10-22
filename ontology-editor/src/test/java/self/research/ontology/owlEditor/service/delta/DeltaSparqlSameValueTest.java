package self.research.ontology.owlEditor.service.delta;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeltaSparqlSameValueTest {

    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI S = VF.createIRI("http://ex.org/Reference");
    private static final IRI P = VF.createIRI("http://ex.org/hasValue");
    private static final String GRAPH = "urn:test:graph";

    private static ExactDelta replacing(String oldLexical, String newLexical) {
        Model deleted = new LinkedHashModel();
        deleted.add(S, P, VF.createLiteral(oldLexical, XSD.DECIMAL));
        Model inserted = new LinkedHashModel();
        inserted.add(S, P, VF.createLiteral(newLexical, XSD.DECIMAL));
        return new ExactDelta(deleted, inserted);
    }

    private static boolean demandsAbsence(List<String> queries, String lexical) {
        return queries.stream().anyMatch(q -> q.contains("FILTER NOT EXISTS") && q.contains("\"" + lexical + "\""));
    }

    @Test
    void aNumberRewrittenWithADifferentSpellingIsNotDemandedAbsentOnUndo() {
        List<String> queries = DeltaSparql.holds(replacing("45.0", "45"), true, GRAPH);

        assertFalse(demandsAbsence(queries, "45.0"));
        assertTrue(queries.stream().anyMatch(q -> q.contains("\"45\"")));
    }

    @Test
    void aNumberRewrittenWithADifferentSpellingIsNotDemandedAbsentOnRedo() {
        List<String> queries = DeltaSparql.holds(replacing("45.0", "45"), false, GRAPH);

        assertFalse(demandsAbsence(queries, "45"));
    }

    @Test
    void aNumberReplacedByADifferentNumberIsStillDemandedAbsent() {
        List<String> queries = DeltaSparql.holds(replacing("45.0", "46"), true, GRAPH);

        assertTrue(demandsAbsence(queries, "45.0"));
    }

    @Test
    void textReplacedByOtherTextIsStillDemandedAbsent() {
        Model deleted = new LinkedHashModel();
        deleted.add(S, P, VF.createLiteral("old"));
        Model inserted = new LinkedHashModel();
        inserted.add(S, P, VF.createLiteral("new"));

        List<String> queries = DeltaSparql.holds(new ExactDelta(deleted, inserted), true, GRAPH);

        assertEquals(2, queries.size());
        assertTrue(demandsAbsence(queries, "old"));
    }
}
