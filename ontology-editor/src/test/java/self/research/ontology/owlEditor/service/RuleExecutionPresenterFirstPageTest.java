package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleExecutionPresenterFirstPageTest {

    private final OWLDataFactory factory = OWLManager.getOWLDataFactory();

    private List<OWLAxiom> conclusions(int count) {
        List<OWLAxiom> out = new ArrayList<>();
        OWLClass person = factory.getOWLClass(IRI.create("urn:t#Person"));
        for (int i = 0; i < count; i++) {
            out.add(factory.getOWLClassAssertionAxiom(person, factory.getOWLNamedIndividual(IRI.create("urn:t#p" + i))));
        }
        return out;
    }

    private List<OWLAxiom> structure(int count) {
        List<OWLAxiom> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(factory.getOWLSubClassOfAxiom(factory.getOWLClass(IRI.create("urn:t#A" + i)), factory.getOWLClass(IRI.create("urn:t#B" + i))));
        }
        return out;
    }

    @Test
    void theFirstPageHoldsOnePageOfConclusionsAndReportsRealTotals() {
        RuleExecutionPresenter.Full full = new RuleExecutionPresenter.Full(conclusions(120), structure(30), 4);

        RuleExecutionPresenter.Presented page = RuleExecutionPresenter.firstPage(full, RuleExecutionPresenter.FIRST_PAGE);

        assertEquals(50, page.ordered().size());
        assertEquals(120, page.conclusionCount());
        assertEquals(30, page.structureCount());
        assertEquals(4, page.omittedTrivial());
        assertTrue(page.truncated());
        assertEquals(50, page.conclusions().size());
    }

    @Test
    void structureFillsTheRoomLeftByFewConclusions() {
        RuleExecutionPresenter.Full full = new RuleExecutionPresenter.Full(conclusions(10), structure(100), 0);

        RuleExecutionPresenter.Presented page = RuleExecutionPresenter.firstPage(full, 50);

        assertEquals(50, page.ordered().size());
        assertEquals(10, page.conclusions().size());
        assertTrue(page.truncated());
    }

    @Test
    void aSmallResultIsNotTruncated() {
        RuleExecutionPresenter.Full full = new RuleExecutionPresenter.Full(conclusions(4), structure(6), 0);

        RuleExecutionPresenter.Presented page = RuleExecutionPresenter.firstPage(full, 50);

        assertEquals(10, page.ordered().size());
        assertFalse(page.truncated());
    }

    @Test
    void theOldLimitStillAppliesWhenNoRunIsKept() {
        RuleExecutionPresenter.Full full = new RuleExecutionPresenter.Full(conclusions(1500), structure(0), 0);

        RuleExecutionPresenter.Presented old = RuleExecutionPresenter.limited(full, RuleExecutionPresenter.MAX_INFERRED);

        assertEquals(1500, old.ordered().size());
        assertFalse(old.truncated());
    }
}
