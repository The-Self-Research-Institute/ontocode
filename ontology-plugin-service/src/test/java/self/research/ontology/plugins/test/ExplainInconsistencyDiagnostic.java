package self.research.ontology.plugins.test;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import self.research.ontology.plugins.service.ReasonerService;
import self.research.ontology.plugins.service.ReasonerType;

import java.io.File;
import java.util.Map;

public class ExplainInconsistencyDiagnostic {
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "../reasonarinconsistant2.owl";
        File file = new File(path);
        System.out.println("Loading: " + file.getAbsolutePath() + " exists=" + file.exists());

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(file);

        System.out.println("Classes: " + ontology.getClassesInSignature().size());
        System.out.println("Individuals: " + ontology.getIndividualsInSignature().size());
        System.out.println("DisjointClasses axioms: "
                + ontology.getAxioms(org.semanticweb.owlapi.model.AxiomType.DISJOINT_CLASSES).size());

        ReasonerService service = new ReasonerService();
        Map<String, Object> result = service.explainInconsistency(ontology, ReasonerType.HERMIT);

        System.out.println("\n=== explainInconsistency result ===");
        result.forEach((k, v) -> System.out.println(k + " = " + v));
    }
}
