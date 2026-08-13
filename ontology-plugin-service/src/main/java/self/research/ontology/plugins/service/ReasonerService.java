package self.research.ontology.plugins.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.*;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import openllet.owlapi.OpenlletReasonerFactory;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import uk.ac.manchester.cs.jfact.JFactFactory;
import org.semanticweb.owl.explanation.api.Explanation;
import org.semanticweb.owl.explanation.api.ExplanationGenerator;
import org.semanticweb.owl.explanation.impl.blackbox.checker.InconsistentOntologyExplanationGeneratorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service("pluginReasonerService")
public class ReasonerService {

    private static final Logger log = LoggerFactory.getLogger(ReasonerService.class);

    private static final OWLDataFactory DATA_FACTORY =
            OWLManager.createOWLOntologyManager().getOWLDataFactory();

    private final Map<String, OWLReasoner> reasonerCache = new ConcurrentHashMap<>();

    @Value("${ontocode.reasoner.per-individual-timeout-ms:5000}")
    private long PER_INDIVIDUAL_TYPE_TIMEOUT_MS;

    @Value("${ontocode.reasoner.realization-budget-ms:20000}")
    private long REALIZATION_TOTAL_BUDGET_MS;
    private final ExecutorService realizationExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "reasoner-realization-worker");
        t.setDaemon(true);
        return t;
    });

    @Value("${ontocode.reasoner.justification-timeout-ms:15000}")
    private long JUSTIFICATION_TIMEOUT_MS;

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

    private OWLReasoner createReasoner(OWLOntology ontology, ReasonerType type) {
        log.info("Creating {} reasoner for ontology: {}",
            type.getDisplayName(),
            ontology.getOntologyID().getOntologyIRI().orElse(null));

        OWLReasonerConfiguration config = new SimpleConfiguration();

        try {
            switch (type) {
                case HERMIT:

                    log.info("Using HermiT (Hypertableau) reasoner");
                    return new ReasonerFactory().createReasoner(ontology, config);

                case PELLET:
                case OPENLLET:

                    log.info("Using Pellet/Openllet reasoner");
                    return OpenlletReasonerFactory.getInstance().createReasoner(ontology, config);

                case FACTPLUSPLUS:

                    log.info("Using FaCT++ (JFact) reasoner");
                    return new JFactFactory().createReasoner(ontology, config);

                case ELK:

                    log.info("Using ELK (Consequence-based EL++) reasoner");
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

    public Set<OWLClass> getUnsatisfiableClasses(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);

        try {

            if (!reasoner.isConsistent()) {
                log.warn("Ontology is inconsistent, returning empty set for unsatisfiable classes");
                return Collections.emptySet();
            }

            Node<OWLClass> bottomNode = reasoner.getUnsatisfiableClasses();
            Set<OWLClass> unsatisfiable = bottomNode.getEntities();

            unsatisfiable.remove(DATA_FACTORY.getOWLNothing());

            log.info("Found {} unsatisfiable classes", unsatisfiable.size());
            return unsatisfiable;
        } catch (Exception e) {
            log.error("Error finding unsatisfiable classes", e);
            return Collections.emptySet();
        }
    }

    public void classify(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);

        try {
            log.info("Starting classification with {}", type.getDisplayName());
            long startTime = System.currentTimeMillis();

            boolean isConsistent = reasoner.isConsistent();
            if (!isConsistent) {
                log.warn("Ontology is inconsistent. Classification may not produce meaningful results.");

            }

            if (type == ReasonerType.ELK) {
                log.info("ELK reasoner: precomputing EL-profile compatible inferences");
                try {
                    reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
                } catch (Exception e) {
                    log.warn("ELK: CLASS_HIERARCHY precomputation failed, trying basic classification", e);

                }
            } else {

                try {
                    reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
                } catch (Exception e) {
                    log.warn("{}: CLASS_HIERARCHY precomputation failed: {}", type.getDisplayName(), e.getMessage());
                }
                try {
                    reasoner.precomputeInferences(InferenceType.OBJECT_PROPERTY_HIERARCHY);
                } catch (Exception e) {
                    log.warn("{}: OBJECT_PROPERTY_HIERARCHY precomputation failed (will use asserted): {}", type.getDisplayName(), e.getMessage());
                }
                try {
                    reasoner.precomputeInferences(InferenceType.DATA_PROPERTY_HIERARCHY);
                } catch (Exception e) {
                    log.warn("{}: DATA_PROPERTY_HIERARCHY precomputation failed (will use asserted): {}", type.getDisplayName(), e.getMessage());
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("Classification completed in {} ms", duration);
        } catch (Exception e) {

            log.warn("Unexpected error during classification setup with {}: {} — continuing with partial results",
                type.getDisplayName(), e.getMessage());
        }
    }

    public Map<String, Object> getClassificationResults(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        Map<String, Object> results = new HashMap<>();

        try {
            OWLClass owlThing = DATA_FACTORY.getOWLThing();

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

            List<Map<String, Object>> classHierarchy = new ArrayList<>();
            try {
                Set<OWLClass> processedClasses = new HashSet<>();
                buildClassHierarchy(reasoner, ontology, owlThing, classHierarchy, processedClasses, 0);
            } catch (Exception e) {
                if (type == ReasonerType.ELK) {
                    log.warn("ELK: Error building class hierarchy - may be due to unsupported OWL constructs", e);
                } else {
                    log.error("Error building class hierarchy, returning empty", e);
                }
            }
            results.put("classHierarchy", classHierarchy);

            List<Map<String, Object>> equivalentClasses = new ArrayList<>();
            try {
                for (OWLClass cls : ontology.getClassesInSignature()) {
                    try {
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
                    } catch (Exception e) {

                        if (type != ReasonerType.ELK) {
                            log.debug("Error processing equivalent classes for {}", cls.getIRI(), e);
                        }
                    }
                }
            } catch (Exception e) {
                if (type == ReasonerType.ELK) {
                    log.warn("ELK: Error finding equivalent classes - may be due to unsupported OWL constructs", e);
                } else {
                    log.error("Error finding equivalent classes, returning empty", e);
                }
            }
            results.put("equivalentClasses", equivalentClasses);

            List<Map<String, String>> unsatisfiableList = new ArrayList<>();
            if (!isConsistent) {
                log.warn("Skipping unsatisfiable classes check for inconsistent ontology");
            } else {
                try {
                    Node<OWLClass> bottomNode = reasoner.getUnsatisfiableClasses();
                    Set<OWLClass> unsatisfiable = new HashSet<>(bottomNode.getEntities());
                    unsatisfiable.remove(DATA_FACTORY.getOWLNothing());

                    unsatisfiableList = unsatisfiable.stream()
                        .map(cls -> Map.of(
                            "iri", cls.getIRI().toString(),
                            "label", getLabel(cls, ontology)
                        ))
                        .collect(Collectors.toList());
                } catch (Exception e) {
                    if (type == ReasonerType.ELK) {
                        log.warn("ELK: Error getting unsatisfiable classes - may be due to unsupported OWL constructs", e);
                    } else {
                        log.error("Error getting unsatisfiable classes", e);
                    }
                }
            }
            results.put("unsatisfiableClasses", unsatisfiableList);

            List<Map<String, Object>> objectPropertyHierarchy = new ArrayList<>();
            if (type == ReasonerType.ELK) {

                log.info("ELK: Providing asserted object properties (no hierarchy inference support)");
                for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
                    if (!prop.isOWLTopObjectProperty() && !prop.isOWLBottomObjectProperty()) {
                        Map<String, Object> node = new HashMap<>();
                        node.put("iri", prop.getIRI().toString());
                        node.put("label", getLabel(prop, ontology));
                        node.put("depth", 0);
                        node.put("childrenCount", 0);
                        objectPropertyHierarchy.add(node);
                    }
                }
            } else {
                try {
                    OWLObjectProperty topObjectProp = DATA_FACTORY.getOWLTopObjectProperty();
                    Set<OWLObjectProperty> processedObjProps = new HashSet<>();
                    buildObjectPropertyHierarchy(reasoner, ontology, topObjectProp, objectPropertyHierarchy, processedObjProps, 0);

                    if (objectPropertyHierarchy.isEmpty()) {
                        log.info("Inferred object property hierarchy empty, falling back to asserted properties");
                        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
                            if (!prop.isOWLTopObjectProperty() && !prop.isOWLBottomObjectProperty()) {
                                Map<String, Object> node = new HashMap<>();
                                node.put("iri", prop.getIRI().toString());
                                node.put("label", getLabel(prop, ontology));
                                node.put("depth", 0);
                                node.put("childrenCount", 0);
                                objectPropertyHierarchy.add(node);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Error building object property hierarchy, falling back to asserted properties", e);

                    for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
                        if (!prop.isOWLTopObjectProperty() && !prop.isOWLBottomObjectProperty()) {
                            Map<String, Object> node = new HashMap<>();
                            node.put("iri", prop.getIRI().toString());
                            node.put("label", getLabel(prop, ontology));
                            node.put("depth", 0);
                            node.put("childrenCount", 0);
                            objectPropertyHierarchy.add(node);
                        }
                    }
                }
            }
            results.put("objectPropertyHierarchy", objectPropertyHierarchy);

            List<Map<String, Object>> dataPropertyHierarchy = new ArrayList<>();
            if (type == ReasonerType.ELK) {

                log.info("ELK: Providing asserted data properties (no hierarchy inference support)");
                for (OWLDataProperty prop : ontology.getDataPropertiesInSignature()) {
                    if (!prop.isOWLTopDataProperty() && !prop.isOWLBottomDataProperty()) {
                        Map<String, Object> node = new HashMap<>();
                        node.put("iri", prop.getIRI().toString());
                        node.put("label", getLabel(prop, ontology));
                        node.put("depth", 0);
                        node.put("childrenCount", 0);
                        dataPropertyHierarchy.add(node);
                    }
                }
            } else {
                try {
                    OWLDataProperty topDataProp = DATA_FACTORY.getOWLTopDataProperty();
                    Set<OWLDataProperty> processedDataProps = new HashSet<>();
                    buildDataPropertyHierarchy(reasoner, ontology, topDataProp, dataPropertyHierarchy, processedDataProps, 0);

                    if (dataPropertyHierarchy.isEmpty()) {
                        log.info("Inferred data property hierarchy empty, falling back to asserted properties");
                        for (OWLDataProperty prop : ontology.getDataPropertiesInSignature()) {
                            if (!prop.isOWLTopDataProperty() && !prop.isOWLBottomDataProperty()) {
                                Map<String, Object> node = new HashMap<>();
                                node.put("iri", prop.getIRI().toString());
                                node.put("label", getLabel(prop, ontology));
                                node.put("depth", 0);
                                node.put("childrenCount", 0);
                                dataPropertyHierarchy.add(node);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Error building data property hierarchy, falling back to asserted properties", e);

                    for (OWLDataProperty prop : ontology.getDataPropertiesInSignature()) {
                        if (!prop.isOWLTopDataProperty() && !prop.isOWLBottomDataProperty()) {
                            Map<String, Object> node = new HashMap<>();
                            node.put("iri", prop.getIRI().toString());
                            node.put("label", getLabel(prop, ontology));
                            node.put("depth", 0);
                            node.put("childrenCount", 0);
                            dataPropertyHierarchy.add(node);
                        }
                    }
                }
            }
            results.put("dataPropertyHierarchy", dataPropertyHierarchy);

            results.put("totalClasses", ontology.getClassesInSignature().size());

            if (type == ReasonerType.ELK) {
                String elkWarning = "ELK reasoner uses EL profile - complex OWL 2 DL constructs may not be classified correctly. " +
                                    "For full OWL 2 DL support, use HermiT or Pellet reasoner.";
                results.put("reasonerCapabilities", Map.of(
                    "reasoner", "ELK",
                    "profile", "EL (subset of OWL 2 DL)",
                    "warning", elkWarning,
                    "supportsFullOWL2", false
                ));
                log.warn(elkWarning);
            } else {
                results.put("reasonerCapabilities", Map.of(
                    "reasoner", type.getDisplayName(),
                    "profile", "OWL 2 DL",
                    "supportsFullOWL2", true
                ));
            }

            log.info("Classification results: {} class nodes, {} object prop nodes, {} data prop nodes",
                classHierarchy.size(), objectPropertyHierarchy.size(), dataPropertyHierarchy.size());

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

    private void buildClassHierarchy(OWLReasoner reasoner, OWLOntology ontology, OWLClass parentClass,
                                     List<Map<String, Object>> hierarchy, Set<OWLClass> processedClasses, int depth) {
        if (processedClasses.contains(parentClass) || depth > 10) {
            return;
        }
        processedClasses.add(parentClass);

        for (Node<OWLClass> node : reasoner.getSubClasses(parentClass, true)) {
            for (OWLClass subClass : node.getEntities()) {
                if (subClass.isOWLNothing()) {
                    continue;
                }

                Map<String, Object> classNode = new HashMap<>();
                classNode.put("iri", subClass.getIRI().toString());
                classNode.put("label", getLabel(subClass, ontology));
                classNode.put("depth", depth);

                int childrenCount = (int) reasoner.getSubClasses(subClass, true)
                    .entities()
                    .filter(c -> !c.isOWLNothing())
                    .count();
                classNode.put("childrenCount", childrenCount);

                hierarchy.add(classNode);

                if (depth < 5 && childrenCount > 0) {
                    buildClassHierarchy(reasoner, ontology, subClass, hierarchy, processedClasses, depth + 1);
                }
            }
        }
    }

    private void buildObjectPropertyHierarchy(OWLReasoner reasoner, OWLOntology ontology, OWLObjectProperty parentProp,
                                             List<Map<String, Object>> hierarchy, Set<OWLObjectProperty> processedProps, int depth) {
        if (processedProps.contains(parentProp) || depth > 10) {
            return;
        }
        processedProps.add(parentProp);

        for (Node<OWLObjectPropertyExpression> node : reasoner.getSubObjectProperties(parentProp, true)) {
            for (OWLObjectPropertyExpression subPropExpr : node.getEntities()) {
                if (subPropExpr.isAnonymous() || subPropExpr.asOWLObjectProperty().isOWLBottomObjectProperty()) {
                    continue;
                }

                OWLObjectProperty subProp = subPropExpr.asOWLObjectProperty();
                Map<String, Object> propNode = new HashMap<>();
                propNode.put("iri", subProp.getIRI().toString());
                propNode.put("label", getLabel(subProp, ontology));
                propNode.put("depth", depth);

                int childrenCount = (int) reasoner.getSubObjectProperties(subProp, true)
                    .entities()
                    .filter(p -> !p.isAnonymous() && !p.asOWLObjectProperty().isOWLBottomObjectProperty())
                    .count();
                propNode.put("childrenCount", childrenCount);

                hierarchy.add(propNode);

                if (depth < 5 && childrenCount > 0) {
                    buildObjectPropertyHierarchy(reasoner, ontology, subProp, hierarchy, processedProps, depth + 1);
                }
            }
        }
    }

    private void buildDataPropertyHierarchy(OWLReasoner reasoner, OWLOntology ontology, OWLDataProperty parentProp,
                                           List<Map<String, Object>> hierarchy, Set<OWLDataProperty> processedProps, int depth) {
        if (processedProps.contains(parentProp) || depth > 10) {
            return;
        }
        processedProps.add(parentProp);

        for (Node<OWLDataProperty> node : reasoner.getSubDataProperties(parentProp, true)) {
            for (OWLDataProperty subProp : node.getEntities()) {
                if (subProp.isOWLBottomDataProperty()) {
                    continue;
                }

                Map<String, Object> propNode = new HashMap<>();
                propNode.put("iri", subProp.getIRI().toString());
                propNode.put("label", getLabel(subProp, ontology));
                propNode.put("depth", depth);

                int childrenCount = (int) reasoner.getSubDataProperties(subProp, true)
                    .entities()
                    .filter(p -> !p.isOWLBottomDataProperty())
                    .count();
                propNode.put("childrenCount", childrenCount);

                hierarchy.add(propNode);

                if (depth < 5 && childrenCount > 0) {
                    buildDataPropertyHierarchy(reasoner, ontology, subProp, hierarchy, processedProps, depth + 1);
                }
            }
        }
    }

    private String getLabel(OWLEntity entity, OWLOntology ontology) {
        return ontology.getAnnotationAssertionAxioms(entity.getIRI()).stream()
            .filter(a -> a.getProperty().isLabel())
            .findFirst()
            .map(a -> a.getValue().asLiteral().map(OWLLiteral::getLiteral).orElse(""))
            .orElse(getLocalName(entity.getIRI().toString()));
    }

    private String getLocalName(String iri) {
        int hashIndex = iri.lastIndexOf('#');
        int slashIndex = iri.lastIndexOf('/');
        int splitIndex = Math.max(hashIndex, slashIndex);
        return splitIndex >= 0 && splitIndex < iri.length() - 1
            ? iri.substring(splitIndex + 1)
            : iri;
    }

    private List<String> findGeneralJustification(OWLOntology ontology) {
        InconsistentOntologyExplanationGeneratorFactory factory =
            new InconsistentOntologyExplanationGeneratorFactory(
                new ReasonerFactory(), DATA_FACTORY, OWLManager::createOWLOntologyManager, JUSTIFICATION_TIMEOUT_MS);
        ExplanationGenerator<OWLAxiom> generator = factory.createExplanationGenerator(ontology);
        OWLAxiom entailment = DATA_FACTORY.getOWLSubClassOfAxiom(DATA_FACTORY.getOWLThing(), DATA_FACTORY.getOWLNothing());
        Set<Explanation<OWLAxiom>> explanations = generator.getExplanations(entailment, 1);
        if (explanations.isEmpty()) {
            return Collections.emptyList();
        }
        Explanation<OWLAxiom> justification = explanations.iterator().next();
        List<String> lines = new ArrayList<>();
        for (OWLAxiom axiom : justification.getAxioms()) {
            lines.add(renderAxiom(axiom, ontology));
        }
        return lines;
    }

    private String renderAxiom(OWLAxiom axiom, OWLOntology ontology) {
        if (axiom instanceof OWLClassAssertionAxiom) {
            OWLClassAssertionAxiom ax = (OWLClassAssertionAxiom) axiom;
            if (!ax.getIndividual().isAnonymous() && !ax.getClassExpression().isAnonymous()) {
                return getLabel(ax.getIndividual().asOWLNamedIndividual(), ontology) + " Type "
                    + getLabel(ax.getClassExpression().asOWLClass(), ontology);
            }
        } else if (axiom instanceof OWLSubClassOfAxiom) {
            OWLSubClassOfAxiom ax = (OWLSubClassOfAxiom) axiom;
            if (!ax.getSubClass().isAnonymous() && !ax.getSuperClass().isAnonymous()) {
                return getLabel(ax.getSubClass().asOWLClass(), ontology) + " SubClassOf "
                    + getLabel(ax.getSuperClass().asOWLClass(), ontology);
            }
        } else if (axiom instanceof OWLDisjointClassesAxiom) {
            String names = ((OWLDisjointClassesAxiom) axiom).getClassesInSignature().stream()
                .map(c -> getLabel(c, ontology))
                .collect(Collectors.joining(", "));
            return "DisjointClasses: " + names;
        } else if (axiom instanceof OWLDisjointUnionAxiom) {
            OWLDisjointUnionAxiom ax = (OWLDisjointUnionAxiom) axiom;
            String disjuncts = ax.getClassExpressions().stream()
                .filter(ce -> !ce.isAnonymous())
                .map(ce -> getLabel(ce.asOWLClass(), ontology))
                .collect(Collectors.joining(", "));
            return getLabel(ax.getOWLClass(), ontology) + " DisjointUnionOf " + disjuncts;
        } else if (axiom instanceof OWLEquivalentClassesAxiom) {
            String names = ((OWLEquivalentClassesAxiom) axiom).getClassExpressions().stream()
                .filter(ce -> !ce.isAnonymous())
                .map(ce -> getLabel(ce.asOWLClass(), ontology))
                .collect(Collectors.joining(" ≡ "));
            return "EquivalentClasses: " + names;
        } else if (axiom instanceof OWLObjectPropertyAssertionAxiom) {
            OWLObjectPropertyAssertionAxiom ax = (OWLObjectPropertyAssertionAxiom) axiom;
            if (!ax.getSubject().isAnonymous() && !ax.getObject().isAnonymous()) {
                return getLabel(ax.getSubject().asOWLNamedIndividual(), ontology) + " "
                    + getLabel(ax.getProperty().getNamedProperty(), ontology) + " "
                    + getLabel(ax.getObject().asOWLNamedIndividual(), ontology);
            }
        } else if (axiom instanceof OWLDifferentIndividualsAxiom) {
            String names = ((OWLDifferentIndividualsAxiom) axiom).getIndividualsInSignature().stream()
                .map(i -> getLabel(i, ontology))
                .collect(Collectors.joining(", "));
            return "DifferentIndividuals: " + names;
        } else if (axiom instanceof OWLSameIndividualAxiom) {
            String names = ((OWLSameIndividualAxiom) axiom).getIndividualsInSignature().stream()
                .map(i -> getLabel(i, ontology))
                .collect(Collectors.joining(" = "));
            return "SameIndividual: " + names;
        } else if (axiom instanceof OWLFunctionalObjectPropertyAxiom) {
            return "FunctionalObjectProperty: " + getLabel(
                ((OWLFunctionalObjectPropertyAxiom) axiom).getProperty().getNamedProperty(), ontology);
        } else if (axiom instanceof OWLInverseFunctionalObjectPropertyAxiom) {
            return "InverseFunctionalObjectProperty: " + getLabel(
                ((OWLInverseFunctionalObjectPropertyAxiom) axiom).getProperty().getNamedProperty(), ontology);
        }

        String rendered = axiom.toString();
        for (OWLEntity entity : axiom.getSignature()) {
            String label = getLabel(entity, ontology);
            String iri = entity.getIRI().toString();
            if (label != null && !label.isBlank() && !label.equals(iri)) {
                rendered = rendered.replace("<" + iri + ">", label);
            }
        }
        return rendered;
    }

    public void realize(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);

        try {

            boolean isConsistent = reasoner.isConsistent();
            if (!isConsistent) {
                log.warn("Ontology is inconsistent - skipping realization precomputation");
                return;
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

    private NodeSet<OWLClass> getTypesWithTimeout(OWLReasoner reasoner, OWLNamedIndividual individual)
            throws TimeoutException {
        CompletableFuture<NodeSet<OWLClass>> future = CompletableFuture.supplyAsync(
            () -> reasoner.getTypes(individual, true), realizationExecutor);
        try {
            return future.get(PER_INDIVIDUAL_TYPE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            reasoner.interrupt();

            future.whenComplete((result, ex) -> evictInterruptedReasoner(reasoner));
            throw te;
        } catch (Exception e) {
            throw new RuntimeException("Error getting types for individual " + individual.getIRI(), e);
        }
    }

    private void evictInterruptedReasoner(OWLReasoner reasoner) {
        reasonerCache.entrySet().removeIf(entry -> {
            if (entry.getValue() != reasoner) {
                return false;
            }
            try {
                reasoner.dispose();
            } catch (Exception e) {
                log.warn("Error disposing interrupted reasoner {}", entry.getKey(), e);
            }
            log.warn("Evicted reasoner {} after interrupt — will be recreated on next use", entry.getKey());
            return true;
        });
    }

    public Map<String, Object> getRealizationResults(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        Map<String, Object> results = new HashMap<>();

        try {

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
            boolean timedOut = false;

            if (!isConsistent) {

                log.warn("Skipping instance type computation for inconsistent ontology");
            } else {

                try {
                    long loopDeadline = System.currentTimeMillis() + REALIZATION_TOTAL_BUDGET_MS;
                    for (OWLNamedIndividual individual : ontology.getIndividualsInSignature()) {
                        if (System.currentTimeMillis() > loopDeadline) {

                            log.warn("Realization results computation exceeded {} ms budget with {} of {} "
                                    + "individuals processed — returning partial results",
                                REALIZATION_TOTAL_BUDGET_MS, instances.isEmpty() ? 0 : instances.size(),
                                ontology.getIndividualsInSignature().size());
                            timedOut = true;
                            break;
                        }

                        NodeSet<OWLClass> types;
                        try {
                            types = getTypesWithTimeout(reasoner, individual);
                        } catch (TimeoutException te) {

                            log.warn("Timed out computing types for individual {} ({} classes in signature) — "
                                    + "returning partial realization results",
                                individual.getIRI(), ontology.getClassesInSignature().size());
                            timedOut = true;
                            break;
                        }

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
            results.put("timedOut", timedOut);
            if (timedOut) {
                results.put("message", "Instance type computation took too long on this ontology's class "
                        + "hierarchy and was stopped early; results are partial.");
            }

            log.info("Realization results: {} instances computed for {} individuals{}",
                instances.size(), ontology.getIndividualsInSignature().size(),
                timedOut ? " (timed out — partial)" : "");

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

    public Set<OWLClass> getInferredTypes(OWLOntology ontology, OWLNamedIndividual individual, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);

        try {
            NodeSet<OWLClass> types = getTypesWithTimeout(reasoner, individual);
            return types.getFlattened();
        } catch (TimeoutException te) {
            log.warn("Timed out computing inferred types for individual {} ({} classes in signature)",
                individual.getIRI(), ontology.getClassesInSignature().size());
            return Collections.emptySet();
        } catch (Exception e) {
            log.error("Error getting inferred types", e);
            return Collections.emptySet();
        }
    }

    public Set<OWLAxiom> getInferredAxioms(OWLOntology ontology, ReasonerType type) {
        Set<OWLAxiom> inferredAxioms = new HashSet<>();

        try {
            log.info("Generating inferred axioms");

            for (OWLClass owlClass : ontology.getClassesInSignature()) {
                if (owlClass.isOWLThing() || owlClass.isOWLNothing()) {
                    continue;
                }

                Set<OWLClass> superClasses = getInferredSuperClasses(ontology, owlClass, type);
                for (OWLClass superClass : superClasses) {
                    if (!superClass.isOWLThing()) {
                        inferredAxioms.add(DATA_FACTORY.getOWLSubClassOfAxiom(owlClass, superClass));
                    }
                }

                Set<OWLNamedIndividual> instances = getInferredInstances(ontology, owlClass, type);
                for (OWLNamedIndividual individual : instances) {
                    inferredAxioms.add(DATA_FACTORY.getOWLClassAssertionAxiom(owlClass, individual));
                }
            }

            log.info("Generated {} inferred axioms", inferredAxioms.size());
            return inferredAxioms;

        } catch (Exception e) {
            log.error("Error generating inferred axioms", e);
            return Collections.emptySet();
        }
    }

    public Map<String, Object> getReasonerStats(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        Map<String, Object> stats = new HashMap<>();

        try {
            stats.put("reasonerType", type.getDisplayName());
            stats.put("reasonerName", reasoner.getReasonerName());
            stats.put("reasonerVersion", reasoner.getReasonerVersion().toString());

            boolean isConsistent = reasoner.isConsistent();
            stats.put("isConsistent", isConsistent);

            int classCount = ontology.getClassesInSignature().size();
            int objectPropertyCount = ontology.getObjectPropertiesInSignature().size();
            int dataPropertyCount = ontology.getDataPropertiesInSignature().size();
            int individualCount = ontology.getIndividualsInSignature().size();

            stats.put("classCount", classCount);
            stats.put("objectPropertyCount", objectPropertyCount);
            stats.put("dataPropertyCount", dataPropertyCount);
            stats.put("propertyCount", objectPropertyCount + dataPropertyCount);
            stats.put("individualCount", individualCount);

            if (isConsistent) {
                Node<OWLClass> bottomNode = reasoner.getUnsatisfiableClasses();
                int unsatisfiableCount = bottomNode.getSize() - 1;
                stats.put("unsatisfiableClasses", Math.max(0, unsatisfiableCount));
                stats.put("satisfiableClasses", classCount - Math.max(0, unsatisfiableCount));
            } else {
                stats.put("unsatisfiableClasses", -1);
                stats.put("satisfiableClasses", 0);
            }

            stats.put("logicalAxiomCount", ontology.getLogicalAxiomCount());
            stats.put("totalAxiomCount", ontology.getAxiomCount());

            stats.put("supportsIncrementalReasoning", false);
            stats.put("supportsDatatypeReasoning", type != ReasonerType.STRUCTURAL);
            stats.put("supportsOWL2DL", type == ReasonerType.HERMIT || type == ReasonerType.PELLET);

            return stats;

        } catch (Exception e) {
            log.error("Error getting reasoner stats", e);
            return stats;
        }
    }

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

            Map<String, Object> globalNote = new HashMap<>();
            globalNote.put("type", "GLOBAL_INCONSISTENCY");
            globalNote.put("severity", "INFO");
            globalNote.put("title", "Every Class Is Vacuously Unsatisfiable");
            globalNote.put("description", "The ontology as a whole has no valid models, so every class is "
                + "technically equivalent to owl:Nothing. The specific causes below identify which asserted "
                + "axioms are actually responsible.");
            causes.add(globalNote);

            try {
                List<Map<String, Object>> disjointViolations = findDisjointClassViolations(ontology);
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

            try {
                List<Map<String, Object>> propertyViolations = findPropertyViolations(ontology);
                if (!propertyViolations.isEmpty()) {
                    Map<String, Object> cause = new HashMap<>();
                    cause.put("type", "PROPERTY_VIOLATIONS");
                    cause.put("severity", "ERROR");
                    cause.put("title", "Property Domain/Range Conflicts");
                    cause.put("description", "A property assertion entails a type for one of its endpoints "
                        + "(via ObjectPropertyDomain/Range) that is declared disjoint with a type the individual "
                        + "already has asserted");
                    cause.put("violations", propertyViolations);
                    causes.add(cause);
                }
            } catch (Exception e) {
                log.error("Error checking property violations", e);
            }

            if (causes.size() == 1) {
                try {
                    List<String> justification = findGeneralJustification(ontology);
                    if (!justification.isEmpty()) {
                        Map<String, Object> cause = new HashMap<>();
                        cause.put("type", "GENERAL_JUSTIFICATION");
                        cause.put("severity", "ERROR");
                        cause.put("title", "Minimal Inconsistency Justification");
                        cause.put("description", "A minimal set of asserted axioms that together make the "
                            + "ontology inconsistent, found via black-box justification search — the same "
                            + "technique Protégé's Explanation panel uses — rather than the pattern checks above");
                        cause.put("axioms", justification);
                        causes.add(cause);
                    }
                } catch (Exception e) {
                    log.error("Error running general justification search", e);
                }
            }

            Map<String, Object> recommendations = new HashMap<>();
            recommendations.put("type", "RECOMMENDATIONS");
            recommendations.put("title", "How to Fix");
            List<String> tips = new ArrayList<>();
            tips.add("Review the disjoint/property violations listed above");
            tips.add("Check for conflicting disjointness declarations");
            tips.add("Examine cardinality restrictions (min/max constraints)");
            tips.add("Verify property domain and range definitions");
            tips.add("Look for circular or contradictory class definitions");
            recommendations.put("tips", tips);
            causes.add(recommendations);

            explanation.put("causes", causes);
            explanation.put("totalIssues", causes.stream()
                .filter(c -> !"RECOMMENDATIONS".equals(c.get("type")) && !"GLOBAL_INCONSISTENCY".equals(c.get("type")))
                .count());

            return explanation;

        } catch (Exception e) {
            log.error("Error explaining inconsistency", e);
            explanation.put("error", e.getMessage());
            return explanation;
        }
    }

    private List<Map<String, Object>> findDisjointClassViolations(OWLOntology ontology) {
        List<Map<String, Object>> violations = new ArrayList<>();

        List<OWLDisjointClassesAxiom> disjointAxioms =
            new ArrayList<>(ontology.getAxioms(AxiomType.DISJOINT_CLASSES));
        if (disjointAxioms.isEmpty()) {
            return violations;
        }

        Map<OWLNamedIndividual, TypeProvenance> provenanceByIndividual = new HashMap<>();
        for (OWLNamedIndividual individual : ontology.getIndividualsInSignature()) {
            provenanceByIndividual.put(individual, getAssertedTypesClosureWithProvenance(ontology, individual));
        }

        for (OWLDisjointClassesAxiom axiom : disjointAxioms) {
            List<OWLClass> disjointClasses = axiom.getClassesInSignature().stream()
                .filter(c -> !c.isAnonymous())
                .collect(Collectors.toList());

            if (disjointClasses.size() < 2) {
                continue;
            }

            for (Map.Entry<OWLNamedIndividual, TypeProvenance> entry : provenanceByIndividual.entrySet()) {
                TypeProvenance provenance = entry.getValue();
                List<OWLClass> violatingClasses = disjointClasses.stream()
                    .filter(provenance.cameFrom::containsKey)
                    .collect(Collectors.toList());

                if (violatingClasses.size() > 1) {
                    Map<String, Object> violation = new HashMap<>();
                    violation.put("individual", getLabel(entry.getKey(), ontology));
                    violation.put("individualIri", entry.getKey().getIRI().toString());
                    violation.put("disjointClasses", violatingClasses.stream()
                        .map(c -> getLabel(c, ontology))
                        .collect(Collectors.toList()));

                    violation.put("typeDerivations", violatingClasses.stream()
                        .map(c -> {
                            Map<String, Object> derivation = new HashMap<>();
                            derivation.put("class", getLabel(c, ontology));
                            derivation.put("via", buildDerivationChain(c, provenance, ontology));
                            return derivation;
                        })
                        .collect(Collectors.toList()));
                    violations.add(violation);

                    if (violations.size() >= 5) {
                        return violations;
                    }
                }
            }
        }

        return violations;
    }

    private Set<OWLClass> getAssertedTypesClosure(OWLOntology ontology, OWLNamedIndividual individual) {
        return getAssertedTypesClosureWithProvenance(ontology, individual).cameFrom.keySet();
    }

    private static final class TypeProvenance {
        final Map<OWLClass, OWLClass> cameFrom;
        final Map<OWLClass, String> unionDerivations;
        TypeProvenance(Map<OWLClass, OWLClass> cameFrom, Map<OWLClass, String> unionDerivations) {
            this.cameFrom = cameFrom;
            this.unionDerivations = unionDerivations;
        }
    }

    private TypeProvenance getAssertedTypesClosureWithProvenance(OWLOntology ontology, OWLNamedIndividual individual) {
        Map<OWLClass, OWLClass> cameFrom = new LinkedHashMap<>();
        Map<OWLClass, String> unionDerivations = new LinkedHashMap<>();
        Deque<OWLClass> frontier = new ArrayDeque<>();

        for (OWLClassAssertionAxiom ax : ontology.getClassAssertionAxioms(individual)) {
            OWLClassExpression ce = ax.getClassExpression();
            if (!ce.isAnonymous()) {
                OWLClass cls = ce.asOWLClass();
                if (!cameFrom.containsKey(cls)) {
                    cameFrom.put(cls, null);
                    frontier.push(cls);
                }
            }
        }

        while (!frontier.isEmpty()) {
            OWLClass current = frontier.pop();

            for (OWLSubClassOfAxiom ax : ontology.getSubClassAxiomsForSubClass(current)) {
                OWLClassExpression sup = ax.getSuperClass();
                if (!sup.isAnonymous()) {
                    OWLClass supCls = sup.asOWLClass();
                    if (!cameFrom.containsKey(supCls)) {
                        cameFrom.put(supCls, current);
                        frontier.push(supCls);
                    }
                }
            }

            for (OWLEquivalentClassesAxiom ax : ontology.getEquivalentClassesAxioms(current)) {
                for (OWLClassExpression member : ax.getClassExpressions()) {
                    if (!member.isAnonymous()) {
                        OWLClass memberCls = member.asOWLClass();
                        if (!cameFrom.containsKey(memberCls)) {
                            cameFrom.put(memberCls, current);
                            frontier.push(memberCls);
                        }
                    }
                }
            }

            for (OWLDisjointUnionAxiom duAxiom : ontology.getDisjointUnionAxioms(current)) {
                List<OWLClass> disjuncts = duAxiom.getClassExpressions().stream()
                    .filter(ce -> !ce.isAnonymous())
                    .map(OWLClassExpression::asOWLClass)
                    .collect(Collectors.toList());
                if (disjuncts.isEmpty()) {
                    continue;
                }

                Set<OWLClass> commonAncestors = null;
                for (OWLClass disjunct : disjuncts) {
                    Set<OWLClass> supers = computeAllSuperclasses(ontology, disjunct);
                    commonAncestors = (commonAncestors == null) ? new HashSet<>(supers)
                        : intersect(commonAncestors, supers);
                }
                if (commonAncestors == null) {
                    continue;
                }

                String disjunctLabels = disjuncts.stream()
                    .map(d -> getLabel(d, ontology))
                    .collect(Collectors.joining(" or "));
                for (OWLClass ancestor : commonAncestors) {
                    if (!cameFrom.containsKey(ancestor)) {
                        cameFrom.put(ancestor, current);
                        unionDerivations.put(ancestor, getLabel(current, ontology) + " is a disjoint union of "
                            + disjunctLabels + " — both are " + getLabel(ancestor, ontology));
                        frontier.push(ancestor);
                    }
                }
            }
        }

        return new TypeProvenance(cameFrom, unionDerivations);
    }

    private Set<OWLClass> computeAllSuperclasses(OWLOntology ontology, OWLClass start) {
        Set<OWLClass> result = new HashSet<>();
        Deque<OWLClass> frontier = new ArrayDeque<>();
        result.add(start);
        frontier.push(start);
        while (!frontier.isEmpty()) {
            OWLClass current = frontier.pop();
            for (OWLSubClassOfAxiom ax : ontology.getSubClassAxiomsForSubClass(current)) {
                OWLClassExpression sup = ax.getSuperClass();
                if (!sup.isAnonymous() && result.add(sup.asOWLClass())) {
                    frontier.push(sup.asOWLClass());
                }
            }
            for (OWLEquivalentClassesAxiom ax : ontology.getEquivalentClassesAxioms(current)) {
                for (OWLClassExpression member : ax.getClassExpressions()) {
                    if (!member.isAnonymous() && result.add(member.asOWLClass())) {
                        frontier.push(member.asOWLClass());
                    }
                }
            }
        }
        return result;
    }

    private Set<OWLClass> intersect(Set<OWLClass> a, Set<OWLClass> b) {
        Set<OWLClass> result = new HashSet<>(a);
        result.retainAll(b);
        return result;
    }

    private String buildDerivationChain(OWLClass cls, TypeProvenance provenance, OWLOntology ontology) {
        if (provenance.unionDerivations.containsKey(cls)) {
            return provenance.unionDerivations.get(cls);
        }
        if (provenance.cameFrom.get(cls) == null) {
            return null;
        }
        List<String> chain = new ArrayList<>();
        OWLClass current = cls;
        while (current != null) {
            chain.add(0, getLabel(current, ontology));
            current = provenance.cameFrom.get(current);
        }
        return String.join(" ⊑ ", chain);
    }

    private List<Map<String, Object>> findPropertyViolations(OWLOntology ontology) {
        List<Map<String, Object>> violations = new ArrayList<>();

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

            if (domains.isEmpty() && ranges.isEmpty()) {
                continue;
            }

            for (OWLObjectPropertyAssertionAxiom assertion : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)) {
                if (assertion.getProperty().isAnonymous() || !assertion.getProperty().asOWLObjectProperty().equals(prop)) {
                    continue;
                }
                if (assertion.getSubject().isAnonymous() || assertion.getObject().isAnonymous()) {
                    continue;
                }
                OWLNamedIndividual subject = assertion.getSubject().asOWLNamedIndividual();
                OWLNamedIndividual object = assertion.getObject().asOWLNamedIndividual();

                if (!domains.isEmpty()) {
                    Set<OWLClass> subjectTypes = getAssertedTypesClosure(ontology, subject);
                    for (OWLClass domain : domains) {
                        OWLClass conflict = findDisjointConflict(ontology, subjectTypes, domain);
                        if (conflict != null) {
                            violations.add(buildPropertyViolation(
                                prop, "domain", subject, domain, conflict, ontology));
                            if (violations.size() >= 5) {
                                return violations;
                            }
                        }
                    }
                }

                if (!ranges.isEmpty()) {
                    Set<OWLClass> objectTypes = getAssertedTypesClosure(ontology, object);
                    for (OWLClass range : ranges) {
                        OWLClass conflict = findDisjointConflict(ontology, objectTypes, range);
                        if (conflict != null) {
                            violations.add(buildPropertyViolation(
                                prop, "range", object, range, conflict, ontology));
                            if (violations.size() >= 5) {
                                return violations;
                            }
                        }
                    }
                }
            }
        }

        return violations;
    }

    private OWLClass findDisjointConflict(OWLOntology ontology, Set<OWLClass> assertedTypes, OWLClass required) {
        if (assertedTypes.contains(required)) {
            return null;
        }
        for (OWLDisjointClassesAxiom axiom : ontology.getDisjointClassesAxioms(required)) {
            for (OWLClass other : axiom.getClassesInSignature()) {
                if (!other.equals(required) && assertedTypes.contains(other)) {
                    return other;
                }
            }
        }
        return null;
    }

    private Map<String, Object> buildPropertyViolation(OWLObjectProperty prop, String constraintKind,
            OWLNamedIndividual individual, OWLClass required, OWLClass conflict, OWLOntology ontology) {
        Map<String, Object> violation = new HashMap<>();
        violation.put("property", getLabel(prop, ontology));
        violation.put("propertyIri", prop.getIRI().toString());
        violation.put("constraintKind", constraintKind);
        violation.put("individual", getLabel(individual, ontology));
        violation.put("individualIri", individual.getIRI().toString());
        violation.put("requiredClass", getLabel(required, ontology));
        violation.put("conflictingClass", getLabel(conflict, ontology));
        return violation;
    }

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

    public void disposeReasoners(OWLOntology ontology) {
        String keyPrefix = ontology.getOntologyID().toString() + "-";
        Iterator<Map.Entry<String, OWLReasoner>> it = reasonerCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, OWLReasoner> entry = it.next();
            if (!entry.getKey().startsWith(keyPrefix)) {
                continue;
            }
            it.remove();
            try {
                if (entry.getValue() != null) {
                    entry.getValue().dispose();
                }
                log.info("Disposed reasoner {} for evicted ontology", entry.getKey());
            } catch (Exception e) {
                log.warn("Error disposing reasoner {}", entry.getKey(), e);
            }
        }
    }

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
