package self.research.ontology.owlEditor.hierarchy;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import self.research.ontology.owlEditor.dto.OntologyDto;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class HierarchySnapshotBuilderPizzaTest {

    private static final String PERSON =
            "http://www.semanticweb.org/pizzatutorial/ontologies/2020/PizzaTutorial#Person";
    private static final String PIZZA =
            "http://www.semanticweb.org/pizzatutorial/ontologies/2020/PizzaTutorial#Pizza";
    private static final String EMPLOYEE =
            "http://www.semanticweb.org/pizzatutorial/ontologies/2020/PizzaTutorial#Employee";
    private static final String PIZZA_BASE =
            "http://www.semanticweb.org/pizzatutorial/ontologies/2020/PizzaTutorial#PizzaBase";
    private static final String PIZZA_TOPPING =
            "http://www.semanticweb.org/pizzatutorial/ontologies/2020/PizzaTutorial#PizzaTopping";

    @Test
    void assertedTopLevel_matchesOntoCodePizzaTutorial() throws Exception {
        Path owl = Path.of("..", "PizzaTutorialWithDataV2.owl").normalize();
        assertTrue(owl.toFile().exists(), "Pizza tutorial fixture missing: " + owl);

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ont = manager.loadOntologyFromOntologyDocument(owl.toFile());
        HierarchySnapshotBuilder builder = new HierarchySnapshotBuilder();

        Set<String> roots = builder.buildTopLevelAsserted(ont, 500, 0).stream()
                .map(OntologyDto.TreeNode::getId)
                .collect(Collectors.toSet());

        assertTrue(roots.contains(PERSON), "Person should be a top-level root");
        assertTrue(roots.contains(PIZZA), "Pizza should be a top-level root");
        assertTrue(roots.contains(PIZZA_BASE), "PizzaBase should be a top-level root");
        assertTrue(roots.contains(PIZZA_TOPPING), "PizzaTopping should be a top-level root");
        assertFalse(roots.contains(EMPLOYEE), "Employee should be under Person, not top-level");

        Set<String> personChildren = builder.buildChildren(ont, null, PERSON, 500, 0).stream()
                .map(OntologyDto.TreeNode::getId)
                .collect(Collectors.toSet());
        assertTrue(personChildren.contains(EMPLOYEE), "Employee should be a child of Person");
    }
}
