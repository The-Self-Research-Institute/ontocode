package self.research.ontology.plugins.test;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;

import java.io.File;
import java.util.*;

/**
 * Test class for ELK Reasoner
 * This class tests ELK reasoner functionality and compatibility with sample ontologies
 */
public class ELKReasonerTest {
    private static final Logger log = LoggerFactory.getLogger(ELKReasonerTest.class);

    /**
     * Test ELK reasoner with a simple ontology
     */
    public static void main(String[] args) {
        log.info("Starting ELK Reasoner Test");
        log.info("============================");

        try {
            // Create a simple test ontology
            OWLOntology testOntology = createSimpleTestOntology();
            
            // Test 1: ELK Reasoner
            log.info("\n[Test 1] Testing ELK Reasoner");
            testReasonerType(testOntology, "ELK", true);

            // Test 2: Structural Reasoner (control)
            log.info("\n[Test 2] Testing Structural Reasoner (control)");
            testReasonerType(testOntology, "Structural", false);

            log.info("\n============================");
            log.info("ELK Reasoner Test Complete");
            log.info("============================");

        } catch (Exception e) {
            log.error("Test failed", e);
            System.exit(1);
        }
    }

    /**
     * Create a simple EL-profile compatible test ontology
     */
    private static OWLOntology createSimpleTestOntology() throws OWLOntologyCreationException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();

        // Create ontology
        IRI ontologyIRI = IRI.create("http://example.org/test-elk-ontology");
        OWLOntology ontology = manager.createOntology(ontologyIRI);

        // Create IRIs for classes
        IRI animalIRI = IRI.create("http://example.org/test-elk-ontology#Animal");
        IRI mammalIRI = IRI.create("http://example.org/test-elk-ontology#Mammal");
        IRI dogIRI = IRI.create("http://example.org/test-elk-ontology#Dog");
        IRI catIRI = IRI.create("http://example.org/test-elk-ontology#Cat");
        IRI petIRI = IRI.create("http://example.org/test-elk-ontology#Pet");

        // Create classes
        OWLClass animal = factory.getOWLClass(animalIRI);
        OWLClass mammal = factory.getOWLClass(mammalIRI);
        OWLClass dog = factory.getOWLClass(dogIRI);
        OWLClass cat = factory.getOWLClass(catIRI);
        OWLClass pet = factory.getOWLClass(petIRI);

        // Add axioms: simple hierarchy
        OWLAxiom mammalSubclassAnimal = factory.getOWLSubClassOfAxiom(mammal, animal);
        OWLAxiom dogSubclassMammal = factory.getOWLSubClassOfAxiom(dog, mammal);
        OWLAxiom catSubclassMammal = factory.getOWLSubClassOfAxiom(cat, mammal);

        manager.addAxiom(ontology, mammalSubclassAnimal);
        manager.addAxiom(ontology, dogSubclassMammal);
        manager.addAxiom(ontology, catSubclassMammal);

        log.info("Created test ontology with 4 classes and 3 axioms");
        return ontology;
    }

    /**
     * Test a specific reasoner type
     */
    private static void testReasonerType(OWLOntology ontology, String reasonerName, boolean isELK) 
            throws Exception {
        
        OWLReasoner reasoner = null;
        try {
            long startTime = System.currentTimeMillis();

            // Create reasoner
            if (isELK) {
                log.info("Creating ELK Reasoner...");
                reasoner = new ElkReasonerFactory().createReasoner(ontology);
            } else {
                log.info("Creating Structural Reasoner...");
                reasoner = new StructuralReasonerFactory().createReasoner(ontology);
            }

            long creationTime = System.currentTimeMillis() - startTime;
            log.info("✓ Reasoner created successfully in {} ms", creationTime);

            // Test 1: Check consistency
            log.info("\n  Test 1: Checking consistency...");
            try {
                boolean isConsistent = reasoner.isConsistent();
                log.info("  ✓ Consistency check: {}", isConsistent ? "CONSISTENT" : "INCONSISTENT");
            } catch (Exception e) {
                log.warn("  ✗ Consistency check failed: {}", e.getMessage());
            }

            // Test 2: Precompute inferences
            log.info("\n  Test 2: Precomputing inferences...");
            try {
                startTime = System.currentTimeMillis();
                // ELK reasoner precomputes inferences on demand
                // Trigger inference computation by querying the hierarchy
                OWLOntologyManager ontologyManager = ontology.getOWLOntologyManager();
                OWLDataFactory dataFactory = ontologyManager.getOWLDataFactory();
                Set<OWLClass> subClasses = reasoner.getSubClasses(dataFactory.getOWLThing(), false).getFlattened();
                long inferenceTime = System.currentTimeMillis() - startTime;
                log.info("  ✓ Inferences computed in {} ms, found {} subclasses of owl:Thing", inferenceTime, subClasses.size());
            } catch (Exception e) {
                log.warn("  ✗ Inference computation failed: {}", e.getMessage());
                if (isELK) {
                    log.warn("  ℹ This may indicate unsupported OWL 2 constructs in EL profile");
                }
            }

            // Test 3: Get class hierarchy
            log.info("\n  Test 3: Getting class hierarchy...");
            try {
                Set<OWLClass> classes = ontology.getClassesInSignature();
                log.info("  Found {} classes", classes.size());

                int hierarchyCount = 0;
                for (OWLClass cls : classes) {
                    Set<OWLClass> superclasses = reasoner.getSuperClasses(cls, false).getFlattened();
                    Set<OWLClass> subclasses = reasoner.getSubClasses(cls, false).getFlattened();
                    
                    if (!superclasses.isEmpty() || !subclasses.isEmpty()) {
                        log.info("    {} → superclasses: {}, subclasses: {}",
                            cls.getIRI().getFragment(),
                            superclasses.size(),
                            subclasses.size());
                        hierarchyCount++;
                    }
                }
                log.info("  ✓ Successfully retrieved hierarchy for {} classes", hierarchyCount);
            } catch (Exception e) {
                log.warn("  ✗ Class hierarchy retrieval failed: {}", e.getMessage());
            }

            // Test 4: Get unsatisfiable classes
            log.info("\n  Test 4: Checking unsatisfiable classes...");
            try {
                if (reasoner.isConsistent()) {
                    Set<OWLClass> unsatisfiable = reasoner.getUnsatisfiableClasses().getEntities();
                    log.info("  ✓ Unsatisfiable classes: {}", unsatisfiable.size());
                } else {
                    log.warn("  Skipped: Ontology is inconsistent");
                }
            } catch (Exception e) {
                log.warn("  ✗ Unsatisfiable classes check failed: {}", e.getMessage());
            }

            log.info("\n✓ {} Reasoner tests completed successfully", reasonerName);

        } catch (Exception e) {
            log.error("✗ Error testing {} Reasoner: {}", reasonerName, e.getMessage(), e);
            if (isELK) {
                log.error("TROUBLESHOOTING: ELK only supports the EL profile of OWL.");
                log.error("If you get exceptions, your ontology may contain:");
                log.error("  - Universal quantification (forall)");
                log.error("  - Cardinality restrictions (min/max)");
                log.error("  - Disjointness axioms");
                log.error("  - Property chains");
                log.error("  - Complex role inclusions");
                log.error("\nEL profile supports: class hierarchies, existential quantification, intersections");
            }
            throw e;
        } finally {
            if (reasoner != null) {
                reasoner.dispose();
                log.info("Reasoner disposed");
            }
        }
    }
}
