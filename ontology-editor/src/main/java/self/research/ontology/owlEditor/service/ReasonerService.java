package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.*;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.semanticweb.HermiT.ReasonerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for ontology reasoning operations.
 * Supports multiple reasoners: HermiT, Structural, and Openllet.
 */
@Service
public class ReasonerService {

    private static final Logger log = LoggerFactory.getLogger(ReasonerService.class);

    private final Map<String, OWLReasoner> reasonerCache = new HashMap<>();
    
    public enum ReasonerType {
        HERMIT("HermiT"),
        STRUCTURAL("Structural"),
        PELLET("Pellet");  // Openllet is the modern version of Pellet

        private final String displayName;

        ReasonerType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Create or get cached reasoner for an ontology
     */
    public OWLReasoner getReasoner(OWLOntology ontology, ReasonerType type) {
        String cacheKey = ontology.getOntologyID().toString() + "-" + type.name();
        
        if (reasonerCache.containsKey(cacheKey)) {
            OWLReasoner cached = reasonerCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        OWLReasoner reasoner = createReasoner(ontology, type);
        reasonerCache.put(cacheKey, reasoner);
        return reasoner;
    }

    /**
     * Create a new reasoner instance
     */
    private OWLReasoner createReasoner(OWLOntology ontology, ReasonerType type) {
        log.info("Creating {} reasoner for ontology: {}", 
            type.getDisplayName(), 
            ontology.getOntologyID().getOntologyIRI().orElse(null));

        OWLReasonerConfiguration config = new SimpleConfiguration();
        
        try {
            switch (type) {
                case HERMIT:
                    return new ReasonerFactory().createReasoner(ontology, config);
                    
                case PELLET:
                    // Try to use Openllet (modern Pellet)
                    try {
                        Class<?> openlletFactory = Class.forName("openllet.owlapi.OpenlletReasonerFactory");
                        OWLReasonerFactory factory = (OWLReasonerFactory) openlletFactory.getDeclaredConstructor().newInstance();
                        return factory.createReasoner(ontology, config);
                    } catch (ClassNotFoundException e) {
                        log.warn("Openllet not found, falling back to Structural reasoner");
                        return new StructuralReasonerFactory().createReasoner(ontology, config);
                    }
                    
                case STRUCTURAL:
                default:
                    return new StructuralReasonerFactory().createReasoner(ontology, config);
            }
        } catch (Exception e) {
            log.error("Failed to create {} reasoner, falling back to Structural", type, e);
            return new StructuralReasonerFactory().createReasoner(ontology, config);
        }
    }

    /**
     * Check ontology consistency
     */
    public boolean isConsistent(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            boolean consistent = reasoner.isConsistent();
            log.info("Consistency check ({}): {}", type.getDisplayName(), consistent);
            return consistent;
        } catch (Exception e) {
            log.error("Error checking consistency", e);
            throw new RuntimeException("Consistency check failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get all unsatisfiable classes (inconsistent classes)
     */
    public Set<OWLClass> getUnsatisfiableClasses(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            Node<OWLClass> bottomNode = reasoner.getUnsatisfiableClasses();
            Set<OWLClass> unsatisfiable = bottomNode.getEntities();
            
            // Remove owl:Nothing from results
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            unsatisfiable.remove(df.getOWLNothing());
            
            log.info("Found {} unsatisfiable classes", unsatisfiable.size());
            return unsatisfiable;
        } catch (Exception e) {
            log.error("Error finding unsatisfiable classes", e);
            throw new RuntimeException("Failed to find unsatisfiable classes: " + e.getMessage(), e);
        }
    }

    /**
     * Classify the ontology (compute class hierarchy)
     */
    public void classify(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            log.info("Starting classification with {}", type.getDisplayName());
            long startTime = System.currentTimeMillis();
            
            reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Classification completed in {} ms", duration);
        } catch (Exception e) {
            log.error("Error during classification", e);
            throw new RuntimeException("Classification failed: " + e.getMessage(), e);
        }
    }

    /**
     * Realize the ontology (compute instances for all classes)
     */
    public void realize(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            log.info("Starting realization with {}", type.getDisplayName());
            long startTime = System.currentTimeMillis();
            
            reasoner.precomputeInferences(InferenceType.CLASS_ASSERTIONS);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Realization completed in {} ms", duration);
        } catch (Exception e) {
            log.error("Error during realization", e);
            throw new RuntimeException("Realization failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get inferred superclasses for a class
     */
    public Set<OWLClass> getInferredSuperClasses(OWLOntology ontology, OWLClass owlClass, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            NodeSet<OWLClass> superClasses = reasoner.getSuperClasses(owlClass, false);
            return superClasses.getFlattened();
        } catch (Exception e) {
            log.error("Error getting inferred superclasses", e);
            return Collections.emptySet();
        }
    }

    /**
     * Get inferred subclasses for a class
     */
    public Set<OWLClass> getInferredSubClasses(OWLOntology ontology, OWLClass owlClass, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            NodeSet<OWLClass> subClasses = reasoner.getSubClasses(owlClass, false);
            return subClasses.getFlattened();
        } catch (Exception e) {
            log.error("Error getting inferred subclasses", e);
            return Collections.emptySet();
        }
    }

    /**
     * Get inferred instances for a class
     */
    public Set<OWLNamedIndividual> getInferredInstances(OWLOntology ontology, OWLClass owlClass, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            NodeSet<OWLNamedIndividual> individuals = reasoner.getInstances(owlClass, false);
            return individuals.getFlattened();
        } catch (Exception e) {
            log.error("Error getting inferred instances", e);
            return Collections.emptySet();
        }
    }

    /**
     * Get inferred types for an individual
     */
    public Set<OWLClass> getInferredTypes(OWLOntology ontology, OWLNamedIndividual individual, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            NodeSet<OWLClass> types = reasoner.getTypes(individual, false);
            return types.getFlattened();
        } catch (Exception e) {
            log.error("Error getting inferred types", e);
            return Collections.emptySet();
        }
    }

    /**
     * Get all inferred axioms
     */
    public Set<OWLAxiom> getInferredAxioms(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        Set<OWLAxiom> inferredAxioms = new HashSet<>();
        
        try {
            log.info("Generating inferred axioms");
            
            // Get all classes in the ontology
            for (OWLClass owlClass : ontology.getClassesInSignature()) {
                if (owlClass.isOWLThing() || owlClass.isOWLNothing()) {
                    continue;
                }
                
                // Inferred superclass axioms
                Set<OWLClass> superClasses = getInferredSuperClasses(ontology, owlClass, type);
                for (OWLClass superClass : superClasses) {
                    if (!superClass.isOWLThing()) {
                        OWLAxiom axiom = ontology.getOWLOntologyManager()
                            .getOWLDataFactory()
                            .getOWLSubClassOfAxiom(owlClass, superClass);
                        inferredAxioms.add(axiom);
                    }
                }
                
                // Inferred instance axioms
                Set<OWLNamedIndividual> instances = getInferredInstances(ontology, owlClass, type);
                for (OWLNamedIndividual individual : instances) {
                    OWLAxiom axiom = ontology.getOWLOntologyManager()
                        .getOWLDataFactory()
                        .getOWLClassAssertionAxiom(owlClass, individual);
                    inferredAxioms.add(axiom);
                }
            }
            
            log.info("Generated {} inferred axioms", inferredAxioms.size());
            return inferredAxioms;
            
        } catch (Exception e) {
            log.error("Error generating inferred axioms", e);
            return Collections.emptySet();
        }
    }

    /**
     * Explain why a class is unsatisfiable
     */
    public Set<OWLAxiom> explainUnsatisfiability(OWLOntology ontology, OWLClass owlClass, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            Set<OWLClassAxiom> explanation = ontology.getAxioms(owlClass);
            log.info("Found {} axioms in explanation for {}", explanation.size(), owlClass.getIRI());
            return new HashSet<>(explanation);
            
        } catch (Exception e) {
            log.error("Error explaining unsatisfiability", e);
            return Collections.emptySet();
        }
    }

    /**
     * Clear reasoner cache
     */
    public void clearCache() {
        log.info("Clearing reasoner cache ({} entries)", reasonerCache.size());
        
        reasonerCache.values().forEach(reasoner -> {
            try {
                if (reasoner != null) {
                    reasoner.dispose();
                }
            } catch (Exception e) {
                log.warn("Error disposing reasoner", e);
            }
        });
        
        reasonerCache.clear();
    }

    /**
     * Dispose reasoner for specific ontology
     */
    public void disposeReasoner(String ontologyId, ReasonerType type) {
        String cacheKey = ontologyId + "-" + type.name();
        OWLReasoner reasoner = reasonerCache.remove(cacheKey);
        
        if (reasoner != null) {
            try {
                reasoner.dispose();
                log.info("Disposed {} reasoner for ontology {}", type.getDisplayName(), ontologyId);
            } catch (Exception e) {
                log.warn("Error disposing reasoner", e);
            }
        }
    }

    /**
     * Get reasoner statistics
     */
    public Map<String, Object> getReasonerStats(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        Map<String, Object> stats = new HashMap<>();
        
        try {
            stats.put("reasonerType", type.getDisplayName());
            stats.put("reasonerName", reasoner.getReasonerName());
            stats.put("reasonerVersion", reasoner.getReasonerVersion().toString());
            stats.put("isConsistent", reasoner.isConsistent());
            stats.put("classCount", ontology.getClassesInSignature().size());
            stats.put("objectPropertyCount", ontology.getObjectPropertiesInSignature().size());
            stats.put("individualCount", ontology.getIndividualsInSignature().size());
            
            // Get unsatisfiable classes count
            Node<OWLClass> bottomNode = reasoner.getUnsatisfiableClasses();
            int unsatisfiableCount = bottomNode.getSize() - 1; // Exclude owl:Nothing
            stats.put("unsatisfiableClassCount", Math.max(0, unsatisfiableCount));
            
        } catch (Exception e) {
            log.error("Error getting reasoner stats", e);
            stats.put("error", e.getMessage());
        }
        
        return stats;
    }
}