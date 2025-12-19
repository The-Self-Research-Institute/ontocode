package self.research.ontology.plugins.service;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.*;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import openllet.owlapi.OpenlletReasonerFactory;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import uk.ac.manchester.cs.jfact.JFactFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for ontology reasoning operations.
 * Supports multiple reasoners: HermiT, Pellet (Openllet), FaCT++, ELK, and Structural.
 */
@Service
public class ReasonerService {

    private static final Logger log = LoggerFactory.getLogger(ReasonerService.class);

    private final Map<String, OWLReasoner> reasonerCache = new HashMap<>();
    
    public enum ReasonerType {
        HERMIT("HermiT"),
        PELLET("Pellet"),
        OPENLLET("Openllet"),
        FACTPLUSPLUS("FaCT++"),
        ELK("ELK"),
        STRUCTURAL("Structural");

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
                    // HermiT - Hypertableau-based reasoner, best for complex ontologies
                    log.info("Using HermiT (Hypertableau) reasoner");
                    return new ReasonerFactory().createReasoner(ontology, config);
                    
                case PELLET:
                case OPENLLET:
                    // Use Openllet (OWLAPI 5.x compatible reasoner)
                    log.info("Using Pellet/Openllet reasoner");
                    return OpenlletReasonerFactory.getInstance().createReasoner(ontology, config);
                    
                case FACTPLUSPLUS:
                    // FaCT++ (via JFact - Java port)
                    log.info("Using FaCT++ (JFact) reasoner");
                    return new JFactFactory().createReasoner(ontology, config);
                    
                case ELK:
                    // ELK - Fast for EL++ profile ontologies
                    log.info("Using ELK reasoner (optimized for EL++)");
                    return new ElkReasonerFactory().createReasoner(ontology, config);
                    
                case STRUCTURAL:
                default:
                    log.info("Using Structural reasoner (basic)");
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
            // Check consistency first
            if (!reasoner.isConsistent()) {
                log.warn("Ontology is inconsistent, returning empty set for unsatisfiable classes");
                return Collections.emptySet();
            }
            
            Node<OWLClass> bottomNode = reasoner.getUnsatisfiableClasses();
            Set<OWLClass> unsatisfiable = bottomNode.getEntities();
            
            // Remove owl:Nothing from results
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            unsatisfiable.remove(df.getOWLNothing());
            
            log.info("Found {} unsatisfiable classes", unsatisfiable.size());
            return unsatisfiable;
        } catch (Exception e) {
            log.error("Error finding unsatisfiable classes", e);
            return Collections.emptySet();
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
            
            // Check consistency first - if inconsistent, classification may fail
            boolean isConsistent = reasoner.isConsistent();
            if (!isConsistent) {
                log.warn("Ontology is inconsistent. Classification may not produce meaningful results.");
                // Continue anyway - some reasoners can still provide partial results
            }
            
            reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Classification completed in {} ms", duration);
        } catch (Exception e) {
            log.error("Error during classification", e);
            // Check if it's due to inconsistency
            try {
                if (!reasoner.isConsistent()) {
                    log.warn("Classification failed due to inconsistent ontology");
                    // Don't throw exception - let getClassificationResults handle it
                    return;
                }
            } catch (Exception consistencyCheckError) {
                log.error("Could not check consistency", consistencyCheckError);
            }
            throw new RuntimeException("Classification failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get classification results including class hierarchy, equivalent classes, and unsatisfiable classes
     */
    public Map<String, Object> getClassificationResults(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        Map<String, Object> results = new HashMap<>();
        
        try {
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            OWLClass owlThing = df.getOWLThing();
            
            // Check consistency first
            boolean isConsistent = true;
            try {
                isConsistent = reasoner.isConsistent();
                results.put("isConsistent", isConsistent);
                if (!isConsistent) {
                    log.warn("Ontology is inconsistent - providing limited classification results");
                }
            } catch (Exception e) {
                log.error("Error checking consistency", e);
                results.put("isConsistent", false);
                isConsistent = false;
            }
            
            // Build class hierarchy (even for inconsistent ontologies, provide what we can)
            List<Map<String, Object>> classHierarchy = new ArrayList<>();
            try {
                Set<OWLClass> processedClasses = new HashSet<>();
                buildClassHierarchy(reasoner, ontology, owlThing, classHierarchy, processedClasses, 0);
            } catch (Exception e) {
                log.error("Error building class hierarchy, returning empty", e);
            }
            results.put("classHierarchy", classHierarchy);
            
            // Find equivalent classes
            List<Map<String, Object>> equivalentClasses = new ArrayList<>();
            try {
                for (OWLClass cls : ontology.getClassesInSignature()) {
                    Set<OWLClass> equivalents = reasoner.getEquivalentClasses(cls).getEntities();
                    if (equivalents.size() > 1) {
                        Map<String, Object> eqGroup = new HashMap<>();
                        eqGroup.put("classes", equivalents.stream()
                            .map(c -> Map.of(
                                "iri", c.getIRI().toString(),
                                "label", getLabel(c, ontology)
                            ))
                            .collect(Collectors.toList()));
                        equivalentClasses.add(eqGroup);
                    }
                }
            } catch (Exception e) {
                log.error("Error finding equivalent classes, returning empty", e);
            }
            results.put("equivalentClasses", equivalentClasses);
            
            // Get unsatisfiable classes
            List<Map<String, String>> unsatisfiableList = new ArrayList<>();
            if (!isConsistent) {
                log.warn("Skipping unsatisfiable classes check for inconsistent ontology");
            } else {
                try {
                    Node<OWLClass> bottomNode = reasoner.getUnsatisfiableClasses();
                    Set<OWLClass> unsatisfiable = new HashSet<>(bottomNode.getEntities());
                    unsatisfiable.remove(df.getOWLNothing());
                    
                    unsatisfiableList = unsatisfiable.stream()
                        .map(cls -> Map.of(
                            "iri", cls.getIRI().toString(),
                            "label", getLabel(cls, ontology)
                        ))
                        .collect(Collectors.toList());
                } catch (Exception e) {
                    log.error("Error getting unsatisfiable classes", e);
                }
            }
            results.put("unsatisfiableClasses", unsatisfiableList);
            
            // Total counts
            results.put("totalClasses", ontology.getClassesInSignature().size());
            
            log.info("Classification results: {} hierarchy nodes, {} equivalent class groups, {} unsatisfiable classes",
                classHierarchy.size(), equivalentClasses.size(), unsatisfiableList.size());
            
            return results;
            
        } catch (Exception e) {
            log.error("Error getting classification results", e);
            return Map.of(
                "classHierarchy", new ArrayList<>(),
                "equivalentClasses", new ArrayList<>(),
                "unsatisfiableClasses", new ArrayList<>(),
                "totalClasses", ontology.getClassesInSignature().size(),
                "error", e.getMessage()
            );
        }
    }
    
    /**
     * Recursively build class hierarchy
     */
    private void buildClassHierarchy(OWLReasoner reasoner, OWLOntology ontology, OWLClass parentClass,
                                     List<Map<String, Object>> hierarchy, Set<OWLClass> processedClasses, int depth) {
        if (processedClasses.contains(parentClass) || depth > 10) {
            return;
        }
        processedClasses.add(parentClass);
        
        // Get direct subclasses
        for (Node<OWLClass> node : reasoner.getSubClasses(parentClass, true)) {
            for (OWLClass subClass : node.getEntities()) {
                if (subClass.isOWLNothing()) {
                    continue;
                }
                
                Map<String, Object> classNode = new HashMap<>();
                classNode.put("iri", subClass.getIRI().toString());
                classNode.put("label", getLabel(subClass, ontology));
                classNode.put("depth", depth);
                
                // Get children count
                int childrenCount = (int) reasoner.getSubClasses(subClass, true)
                    .entities()
                    .filter(c -> !c.isOWLNothing())
                    .count();
                classNode.put("childrenCount", childrenCount);
                
                hierarchy.add(classNode);
                
                // Recursively process children (limit depth to avoid huge hierarchies)
                if (depth < 5 && childrenCount > 0) {
                    buildClassHierarchy(reasoner, ontology, subClass, hierarchy, processedClasses, depth + 1);
                }
            }
        }
    }
    
    /**
     * Get label for an OWL entity
     */
    private String getLabel(OWLEntity entity, OWLOntology ontology) {
        return ontology.getAnnotationAssertionAxioms(entity.getIRI()).stream()
            .filter(a -> a.getProperty().isLabel())
            .findFirst()
            .map(a -> a.getValue().asLiteral().map(OWLLiteral::getLiteral).orElse(""))
            .orElse(getLocalName(entity.getIRI().toString()));
    }
    
    /**
     * Get local name from IRI
     */
    private String getLocalName(String iri) {
        int hashIndex = iri.lastIndexOf('#');
        int slashIndex = iri.lastIndexOf('/');
        int splitIndex = Math.max(hashIndex, slashIndex);
        return splitIndex >= 0 && splitIndex < iri.length() - 1
            ? iri.substring(splitIndex + 1)
            : iri;
    }

    /**
     * Realize the ontology (compute instances for all classes)
     */
    public void realize(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            // Check consistency first
            boolean isConsistent = reasoner.isConsistent();
            if (!isConsistent) {
                log.warn("Ontology is inconsistent - skipping realization precomputation");
                return; // Return early without throwing exception
            }
            
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
     * Get realization results including instances for all classes
     */
    public Map<String, Object> getRealizationResults(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        Map<String, Object> results = new HashMap<>();
        
        try {
            // Check consistency first
            boolean isConsistent = true;
            try {
                isConsistent = reasoner.isConsistent();
                results.put("isConsistent", isConsistent);
                if (!isConsistent) {
                    log.warn("Ontology is inconsistent - providing limited realization results");
                }
            } catch (Exception e) {
                log.error("Error checking consistency", e);
                results.put("isConsistent", false);
                isConsistent = false;
            }
            
            List<Map<String, Object>> instances = new ArrayList<>();
            
            if (!isConsistent) {
                // For inconsistent ontologies, skip reasoner queries
                log.warn("Skipping instance type computation for inconsistent ontology");
            } else {
                // Get all named individuals and their types
                try {
                    for (OWLNamedIndividual individual : ontology.getIndividualsInSignature()) {
                        NodeSet<OWLClass> types = reasoner.getTypes(individual, false);
                        
                        for (OWLClass cls : types.getFlattened()) {
                            if (!cls.isOWLThing() && !cls.isOWLNothing()) {
                                Map<String, Object> instance = new HashMap<>();
                                instance.put("individualIri", individual.getIRI().toString());
                                instance.put("individualLabel", getLabel(individual, ontology));
                                instance.put("classIri", cls.getIRI().toString());
                                instance.put("classLabel", getLabel(cls, ontology));
                                instances.add(instance);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Error computing instance types, returning empty", e);
                }
            }
            
            results.put("instances", instances);
            results.put("totalInstances", instances.size());
            results.put("totalIndividuals", ontology.getIndividualsInSignature().size());
            
            log.info("Realization results: {} instances computed for {} individuals",
                instances.size(), ontology.getIndividualsInSignature().size());
            
            return results;
            
        } catch (Exception e) {
            log.error("Error getting realization results", e);
            return Map.of(
                "instances", new ArrayList<>(),
                "totalInstances", 0,
                "totalIndividuals", ontology.getIndividualsInSignature().size(),
                "error", e.getMessage()
            );
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
     * Get reasoner statistics
     */
    public Map<String, Object> getReasonerStats(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        Map<String, Object> stats = new HashMap<>();
        
        try {
            stats.put("reasonerType", type.getDisplayName());
            stats.put("reasonerName", reasoner.getReasonerName());
            stats.put("reasonerVersion", reasoner.getReasonerVersion().toString());
            
            // Consistency check
            boolean isConsistent = reasoner.isConsistent();
            stats.put("isConsistent", isConsistent);
            
            // Entity counts
            int classCount = ontology.getClassesInSignature().size();
            int objectPropertyCount = ontology.getObjectPropertiesInSignature().size();
            int dataPropertyCount = ontology.getDataPropertiesInSignature().size();
            int individualCount = ontology.getIndividualsInSignature().size();
            
            stats.put("classCount", classCount);
            stats.put("objectPropertyCount", objectPropertyCount);
            stats.put("dataPropertyCount", dataPropertyCount);
            stats.put("propertyCount", objectPropertyCount + dataPropertyCount);
            stats.put("individualCount", individualCount);
            
            // Unsatisfiable classes
            if (isConsistent) {
                Node<OWLClass> bottomNode = reasoner.getUnsatisfiableClasses();
                int unsatisfiableCount = bottomNode.getSize() - 1; // Exclude owl:Nothing
                stats.put("unsatisfiableClasses", Math.max(0, unsatisfiableCount));
                stats.put("satisfiableClasses", classCount - Math.max(0, unsatisfiableCount));
            } else {
                stats.put("unsatisfiableClasses", -1);
                stats.put("satisfiableClasses", 0);
            }
            
            // Axiom counts
            stats.put("logicalAxiomCount", ontology.getLogicalAxiomCount());
            stats.put("totalAxiomCount", ontology.getAxiomCount());
            
            // Reasoner capabilities
            stats.put("supportsIncrementalReasoning", false);
            stats.put("supportsDatatypeReasoning", type != ReasonerType.STRUCTURAL);
            stats.put("supportsOWL2DL", type == ReasonerType.HERMIT || type == ReasonerType.PELLET);
            
            return stats;
            
        } catch (Exception e) {
            log.error("Error getting reasoner stats", e);
            return stats;
        }
    }
    
    /**
     * Explain why the ontology is inconsistent
     * Returns detailed information about contradictions and problematic axioms
     */
    public Map<String, Object> explainInconsistency(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        Map<String, Object> explanation = new HashMap<>();
        
        try {
            boolean isConsistent = reasoner.isConsistent();
            explanation.put("isConsistent", isConsistent);
            
            if (isConsistent) {
                explanation.put("message", "Ontology is consistent - no explanation needed");
                explanation.put("causes", new ArrayList<>());
                return explanation;
            }
            
            log.info("Analyzing inconsistency causes");
            List<Map<String, Object>> causes = new ArrayList<>();
            
            // 1. Check for unsatisfiable classes
            try {
                Node<OWLClass> bottomNode = reasoner.getUnsatisfiableClasses();
                Set<OWLClass> unsatisfiable = new HashSet<>(bottomNode.getEntities());
                unsatisfiable.remove(ontology.getOWLOntologyManager().getOWLDataFactory().getOWLNothing());
                
                if (!unsatisfiable.isEmpty()) {
                    Map<String, Object> cause = new HashMap<>();
                    cause.put("type", "UNSATISFIABLE_CLASSES");
                    cause.put("severity", "ERROR");
                    cause.put("title", "Found " + unsatisfiable.size() + " Unsatisfiable Class(es)");
                    cause.put("description", "These classes have contradictory axioms that make them equivalent to owl:Nothing");
                    
                    List<Map<String, String>> classList = unsatisfiable.stream()
                        .limit(10) // Limit to avoid huge lists
                        .map(cls -> {
                            Map<String, String> classInfo = new HashMap<>();
                            classInfo.put("iri", cls.getIRI().toString());
                            classInfo.put("label", getLabel(cls, ontology));
                            classInfo.put("reason", analyzeUnsatisfiableClass(cls, ontology, reasoner));
                            return classInfo;
                        })
                        .collect(Collectors.toList());
                    
                    cause.put("classes", classList);
                    causes.add(cause);
                }
            } catch (Exception e) {
                log.error("Error analyzing unsatisfiable classes", e);
            }
            
            // 2. Check for disjoint class violations
            try {
                List<Map<String, Object>> disjointViolations = findDisjointClassViolations(ontology, reasoner);
                if (!disjointViolations.isEmpty()) {
                    Map<String, Object> cause = new HashMap<>();
                    cause.put("type", "DISJOINT_VIOLATIONS");
                    cause.put("severity", "ERROR");
                    cause.put("title", "Disjoint Class Violations");
                    cause.put("description", "Found individuals or class assertions that violate disjointness constraints");
                    cause.put("violations", disjointViolations);
                    causes.add(cause);
                }
            } catch (Exception e) {
                log.error("Error checking disjoint violations", e);
            }
            
            // 3. Check for property domain/range violations
            try {
                List<Map<String, Object>> propertyViolations = findPropertyViolations(ontology);
                if (!propertyViolations.isEmpty()) {
                    Map<String, Object> cause = new HashMap<>();
                    cause.put("type", "PROPERTY_VIOLATIONS");
                    cause.put("severity", "WARNING");
                    cause.put("title", "Potential Property Constraint Violations");
                    cause.put("description", "Found property assertions that may violate domain/range constraints");
                    cause.put("violations", propertyViolations);
                    causes.add(cause);
                }
            } catch (Exception e) {
                log.error("Error checking property violations", e);
            }
            
            // 4. General recommendations
            Map<String, Object> recommendations = new HashMap<>();
            recommendations.put("type", "RECOMMENDATIONS");
            recommendations.put("title", "How to Fix");
            List<String> tips = new ArrayList<>();
            tips.add("Review axioms for the unsatisfiable classes listed above");
            tips.add("Check for conflicting disjointness declarations");
            tips.add("Examine cardinality restrictions (min/max constraints)");
            tips.add("Verify property domain and range definitions");
            tips.add("Look for circular or contradictory class definitions");
            recommendations.put("tips", tips);
            causes.add(recommendations);
            
            explanation.put("causes", causes);
            explanation.put("totalIssues", causes.stream()
                .filter(c -> !"RECOMMENDATIONS".equals(c.get("type")))
                .count());
            
            return explanation;
            
        } catch (Exception e) {
            log.error("Error explaining inconsistency", e);
            explanation.put("error", e.getMessage());
            return explanation;
        }
    }
    
    /**
     * Analyze why a specific class is unsatisfiable
     */
    private String analyzeUnsatisfiableClass(OWLClass cls, OWLOntology ontology, OWLReasoner reasoner) {
        StringBuilder reason = new StringBuilder();
        
        // Check for conflicting restrictions
        Set<OWLSubClassOfAxiom> subClassAxioms = ontology.getSubClassAxiomsForSubClass(cls);
        if (!subClassAxioms.isEmpty()) {
            reason.append("Has ").append(subClassAxioms.size()).append(" subclass axiom(s). ");
        }
        
        // Check for equivalent class axioms
        Set<OWLEquivalentClassesAxiom> equivalentAxioms = ontology.getEquivalentClassesAxioms(cls);
        if (!equivalentAxioms.isEmpty()) {
            reason.append("Has equivalent class definitions. ");
        }
        
        // Check for disjoint declarations
        Set<OWLDisjointClassesAxiom> disjointAxioms = ontology.getDisjointClassesAxioms(cls);
        if (!disjointAxioms.isEmpty()) {
            reason.append("Part of disjoint class declarations. ");
        }
        
        if (reason.length() == 0) {
            reason.append("Check class axioms and restrictions");
        }
        
        return reason.toString().trim();
    }
    
    /**
     * Find disjoint class violations
     */
    private List<Map<String, Object>> findDisjointClassViolations(OWLOntology ontology, OWLReasoner reasoner) {
        List<Map<String, Object>> violations = new ArrayList<>();
        
        for (OWLDisjointClassesAxiom axiom : ontology.getAxioms(AxiomType.DISJOINT_CLASSES)) {
            List<OWLClass> disjointClasses = axiom.getClassesInSignature().stream()
                .filter(c -> !c.isAnonymous())
                .collect(Collectors.toList());
            
            if (disjointClasses.size() >= 2) {
                // Check if any individuals belong to multiple disjoint classes
                for (OWLNamedIndividual individual : ontology.getIndividualsInSignature()) {
                    Set<OWLClass> types = reasoner.getTypes(individual, false).getFlattened();
                    
                    List<OWLClass> violatingClasses = disjointClasses.stream()
                        .filter(types::contains)
                        .collect(Collectors.toList());
                    
                    if (violatingClasses.size() > 1) {
                        Map<String, Object> violation = new HashMap<>();
                        violation.put("individual", getLabel(individual, ontology));
                        violation.put("individualIri", individual.getIRI().toString());
                        violation.put("disjointClasses", violatingClasses.stream()
                            .map(c -> getLabel(c, ontology))
                            .collect(Collectors.toList()));
                        violations.add(violation);
                        
                        if (violations.size() >= 5) break; // Limit results
                    }
                }
            }
            
            if (violations.size() >= 5) break;
        }
        
        return violations;
    }
    
    /**
     * Find property constraint violations
     */
    private List<Map<String, Object>> findPropertyViolations(OWLOntology ontology) {
        List<Map<String, Object>> violations = new ArrayList<>();
        
        // Check object property assertions against domains/ranges
        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
            Set<OWLClass> domains = ontology.getObjectPropertyDomainAxioms(prop).stream()
                .map(OWLObjectPropertyDomainAxiom::getDomain)
                .filter(d -> !d.isAnonymous())
                .map(OWLClassExpression::asOWLClass)
                .collect(Collectors.toSet());
            
            Set<OWLClass> ranges = ontology.getObjectPropertyRangeAxioms(prop).stream()
                .map(OWLObjectPropertyRangeAxiom::getRange)
                .filter(r -> !r.isAnonymous())
                .map(OWLClassExpression::asOWLClass)
                .collect(Collectors.toSet());
            
            if (!domains.isEmpty() || !ranges.isEmpty()) {
                Map<String, Object> propInfo = new HashMap<>();
                propInfo.put("property", getLabel(prop, ontology));
                propInfo.put("propertyIri", prop.getIRI().toString());
                propInfo.put("hasDomainConstraints", !domains.isEmpty());
                propInfo.put("hasRangeConstraints", !ranges.isEmpty());
                violations.add(propInfo);
                
                if (violations.size() >= 5) break;
            }
        }
        
        return violations;
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
}
