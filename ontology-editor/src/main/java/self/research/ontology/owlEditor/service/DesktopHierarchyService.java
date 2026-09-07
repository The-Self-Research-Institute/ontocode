package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.config.FastOpenCondition;
import self.research.ontology.owlEditor.dto.OntologyDto;
import self.research.ontology.owlEditor.hierarchy.HierarchySnapshotBuilder;
import self.research.ontology.owlEditor.hierarchy.OntologyMetricsComputer;
import self.research.ontology.owlEditor.service.owlapi.OwlApiOntologyContext;
import self.research.ontology.owlEditor.service.owlapi.OwlApiQuerySupport;
import self.research.ontology.owlEditor.util.AnnotationValueCollector;

import java.util.*;
import java.util.stream.Collectors;

import static self.research.ontology.owlEditor.service.owlapi.OwlApiQuerySupport.getLabel;

/**
 * OWLAPI in-memory hierarchy (desktop + cloud fast-open).
 */
@Service
@Conditional(FastOpenCondition.class)
public class DesktopHierarchyService {

    private static final Logger log = LoggerFactory.getLogger(DesktopHierarchyService.class);

    @Autowired
    private OwlApiOntologyContext owlApiContext;

    @Autowired
    private ProjectOntologyCache ontologyCache;

    @Autowired
    private HierarchySnapshotBuilder snapshotBuilder;

    @Autowired
    private OntologyMetricsComputer metricsComputer;

    @Autowired(required = false)
    @Qualifier("owlEditorReasonerService")
    private ReasonerService reasonerService;

    public boolean hasOntology(String projectId) {
        return owlApiContext.hasOntology(projectId);
    }

    public Map<String, Object> declarationCounts(String projectId) {
        return ontologyCache.get(projectId)
            .map(c -> c.assertedHierarchyOnly()
                    ? metricsComputer.computeAsserted(c.ontology())
                    : metricsComputer.compute(c.ontology(), c.reasoner()))
            .orElse(Collections.emptyMap());
    }

    public List<OntologyDto.TreeNode> topLevelClasses(String projectId, int limit, int offset) {
        return topLevelClasses(projectId, limit, offset, Imports.EXCLUDED);
    }

    public List<OntologyDto.TreeNode> topLevelClasses(String projectId, int limit, int offset, Imports importsScope) {
        return ontologyCache.get(projectId)
            .map(c -> c.assertedHierarchyOnly()
                    ? snapshotBuilder.buildTopLevelAsserted(c.ontology(), limit, offset, importsScope)
                    : snapshotBuilder.buildTopLevel(c.ontology(), c.reasoner(), limit, offset, importsScope))
            .orElse(Collections.emptyList());
    }

    public int topLevelClassTotal(String projectId) {
        return topLevelClassTotal(projectId, Imports.EXCLUDED);
    }

    public int topLevelClassTotal(String projectId, Imports importsScope) {
        return ontologyCache.get(projectId)
            .map(c -> c.assertedHierarchyOnly()
                    ? snapshotBuilder.countTopLevelAsserted(c.ontology(), importsScope)
                    : snapshotBuilder.countTopLevelCandidates(c.ontology(), c.reasoner(), importsScope))
            .orElse(0);
    }

    /** Flat all-classes list for the graph view — served from the live in-memory model. */
    public List<OntologyDto.TreeNode> allClasses(String projectId, int limit) {
        return ontologyCache.get(projectId)
            .map(c -> snapshotBuilder.buildAllClasses(c.ontology(), limit, Imports.EXCLUDED))
            .orElse(Collections.emptyList());
    }

    public List<OntologyDto.TreeNode> children(String projectId, String parentIri, int limit, int offset) {
        return children(projectId, parentIri, limit, offset, Imports.EXCLUDED);
    }

    public List<OntologyDto.TreeNode> children(String projectId, String parentIri, int limit, int offset,
                                               Imports importsScope) {
        return ontologyCache.get(projectId)
            .map(c -> {
                OWLReasoner r = c.reasoner();
                if (r == null) {
                    r = new org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory()
                            .createNonBufferingReasoner(c.ontology());
                }
                return snapshotBuilder.buildChildren(c.ontology(), r, parentIri, limit, offset, importsScope);
            })
            .orElse(Collections.emptyList());
    }

    /**
     * Batch annotation lookup: returns a map of classIri → annotation value for the given property.
     * Used by the "Render by annotation property" feature.
     */
    public Map<String, String> batchAnnotations(String projectId, List<String> iris, String propertyIri) {
        return ontologyCache.get(projectId)
            .map(c -> {
                Map<String, String> result = new LinkedHashMap<>();
                for (String iri : iris) {
                    String val = snapshotBuilder.getAnnotationValue(c.ontology(), iri, propertyIri);
                    if (val != null) result.put(iri, val);
                }
                return result;
            })
            .orElse(Collections.emptyMap());
    }

    public Map<String, Object> classDetails(String projectId, String classIri) {
        return ontologyCache.get(projectId)
            .map(c -> buildClassDetails(c.ontology(), c.reasoner(), classIri))
            .orElse(Collections.emptyMap());
    }

    public Map<String, Object> classUsage(String projectId, String classIri) {
        return ontologyCache.get(projectId)
            .map(c -> buildClassUsage(c.ontology(), classIri))
            .orElse(Collections.emptyMap());
    }

    public List<Map<String, Object>> classInstances(String projectId, String classIri) {
        return ontologyCache.get(projectId)
            .map(c -> buildClassInstances(c.ontology(), c.reasoner(), classIri))
            .orElse(Collections.emptyList());
    }

    public Map<String, Map<String, Integer>> classInstanceCounts(String projectId) {
        return ontologyCache.get(projectId)
            .map(c -> buildClassInstanceCounts(c.ontology()))
            .orElse(Collections.emptyMap());
    }

    public Map<String, Object> classAnnotations(String projectId, String classIri) {
        return ontologyCache.get(projectId)
            .map(c -> buildClassAnnotations(c.ontology(), classIri))
            .orElse(Collections.emptyMap());
    }

    private Map<String, Object> buildClassAnnotations(OWLOntology ont, String classIri) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass cls = df.getOWLClass(IRI.create(classIri));
        org.semanticweb.owlapi.model.parameters.Imports imp = org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED;
        Map<String, List<String>> annotations = AnnotationValueCollector.newMap();
        ont.annotationAssertionAxioms(cls.getIRI(), imp).forEach(ax -> {
            String prop = ax.getProperty().getIRI().toString();
            ax.getValue().asLiteral().ifPresent(lit ->
                AnnotationValueCollector.add(annotations, prop, lit.getLiteral()));
            ax.getValue().asIRI().ifPresent(iri ->
                AnnotationValueCollector.add(annotations, prop, iri.toString()));
        });
        String rdfsLabel = "http://www.w3.org/2000/01/rdf-schema#label";
        String label = annotations.getOrDefault(rdfsLabel, List.of())
            .stream().findFirst().orElse(cls.getIRI().getShortForm());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", classIri);
        result.put("label", label);
        result.put("annotations", annotations);
        return result;
    }

    private Map<String, Object> buildClassDetails(OWLOntology ont, OWLReasoner reasoner, String classIri) {
        long start = System.currentTimeMillis();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass cls = df.getOWLClass(IRI.create(classIri));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", classIri);

        org.semanticweb.owlapi.model.parameters.Imports imp = org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED;
        Map<String, List<String>> multiAnnotations = AnnotationValueCollector.newMap();
        ont.annotationAssertionAxioms(cls.getIRI(), imp).forEach(ax -> {
            String prop = ax.getProperty().getIRI().toString();
            ax.getValue().asLiteral().ifPresent(lit ->
                AnnotationValueCollector.add(multiAnnotations, prop, lit.getLiteral()));
            ax.getValue().asIRI().ifPresent(iri ->
                AnnotationValueCollector.add(multiAnnotations, prop, iri.toString()));
        });
        Map<String, Object> annotations = new LinkedHashMap<>();
        multiAnnotations.forEach((prop, values) -> {
            if (values.size() == 1) {
                annotations.put(prop, values.get(0));
            } else {
                annotations.put(prop, values);
            }
        });
        String label = multiAnnotations.getOrDefault("http://www.w3.org/2000/01/rdf-schema#label", List.of())
                .stream().findFirst().orElse(null);
        details.put("label", label != null ? label : cls.getIRI().getShortForm());
        details.put("annotations", annotations);

        // subClassOfAxioms: ALL superclasses (named + restrictions) with Manchester syntax definition
        List<OWLClassExpression> subClassExprs = ont.subClassAxiomsForSubClass(cls)
            .map(ax -> ax.getSuperClass())
            .filter(ce -> !ce.isOWLThing() && !ce.isOWLNothing())
            .collect(Collectors.toList());
        List<Map<String, Object>> subClassOfAxioms = new ArrayList<>();
        for (int i = 0; i < subClassExprs.size(); i++) {
            Map<String, Object> m = classExpressionToAxiomMap(ont, subClassExprs.get(i), "sub_" + i);
            m.put("type", "SubClassOf");
            subClassOfAxioms.add(m);
        }
        details.put("subClassOfAxioms", subClassOfAxioms);

        // equivalentClassesAxioms: named + anonymous (restrictions, complex expressions)
        List<OWLClassExpression> eqExprs = ont.equivalentClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls))
            .collect(Collectors.toList());
        List<Map<String, Object>> equivalentClassesAxioms = new ArrayList<>();
        for (int i = 0; i < eqExprs.size(); i++) {
            Map<String, Object> m = classExpressionToAxiomMap(ont, eqExprs.get(i), "eq_" + i);
            m.put("type", "EquivalentTo");
            equivalentClassesAxioms.add(m);
        }
        details.put("equivalentClassesAxioms", equivalentClassesAxioms);

      
        // disjointClassesAxioms: named disjoint classes only. Tag entries that came from an
        // n-ary owl:AllDisjointClasses axiom (3+ members) so the frontend calls the correct
        // delete method — a simple pairwise DELETE/WHERE silently no-ops against this shape.
        List<Map<String, Object>> disjointClassesAxioms = new ArrayList<>();
        Set<String> seenDisjointIris = new LinkedHashSet<>();
        for (OWLDisjointClassesAxiom ax : ont.disjointClassesAxioms(cls).collect(Collectors.toList())) {
            List<OWLClassExpression> members = ax.classExpressions().collect(Collectors.toList());
            boolean isAllDisjointClasses = members.size() >= 3;
            for (OWLClassExpression ce : members) {
                if (ce.equals(cls) || ce.isAnonymous()) continue;
                String iri = ce.asOWLClass().getIRI().toString();
                if (!seenDisjointIris.add(iri)) continue;
                Map<String, Object> m = classExpressionToAxiomMap(ont, ce, "dis_" + disjointClassesAxioms.size());
                m.put("type", "DisjointWith");
                m.put("isAllDisjointClasses", isAllDisjointClasses);
                disjointClassesAxioms.add(m);
            }
        }
        details.put("disjointClassesAxioms", disjointClassesAxioms);

        List<Map<String, String>> unionMembers = ont.equivalentClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls) && ce instanceof OWLObjectUnionOf)
            .flatMap(ce -> ((OWLObjectUnionOf) ce).operands())
            .filter(op -> !op.isAnonymous())
            .map(op -> labeledEntry(ont, op.asOWLClass()))
            .collect(Collectors.toList());
        details.put("unionOfMembers", unionMembers);

        List<Map<String, String>> intersectionMembers = ont.equivalentClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls) && ce instanceof OWLObjectIntersectionOf)
            .flatMap(ce -> ((OWLObjectIntersectionOf) ce).operands())
            .filter(op -> !op.isAnonymous())
            .map(op -> labeledEntry(ont, op.asOWLClass()))
            .collect(Collectors.toList());
        details.put("intersectionOfMembers", intersectionMembers);

        OWLReasoner r = reasoner;
        if (r == null) {
            r = new org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory()
                    .createNonBufferingReasoner(ont);
        }
        List<Map<String, String>> directSubclasses = r.getSubClasses(cls, true)
            .entities()
            .filter(c -> !c.isOWLNothing() && !c.isAnonymous())
            .map(c -> labeledEntry(ont, c))
            .collect(Collectors.toList());
        details.put("directSubclasses", directSubclasses);

        supplementClassDetails(ont, r, classIri, details);
        details.put("inferredFromOwlApi", true);

        log.debug("[Desktop] classDetails({}) in {}ms", classIri, System.currentTimeMillis() - start);
        return details;
    }

    /**
     * OWLAPI in-memory supplements for class details.
     * Covers multi-valued annotations merge, AllDisjointClasses, disjointUnion, hasKey,
     * GCIs, anonymous ancestor axioms, and structural-reasoner inferred axioms.
     */
    private void supplementClassDetails(OWLOntology ont, OWLReasoner reasoner, String classIri,
                                        Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return;
        }
        long start = System.currentTimeMillis();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass cls = df.getOWLClass(IRI.create(classIri));
        org.semanticweb.owlapi.model.parameters.Imports imp =
                org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED;

        

        // --- disjointUnionOf ---
        List<Map<String, Object>> disjointUnionAxioms = new ArrayList<>();
        ont.disjointUnionAxioms(cls).forEach(ax -> {
            List<String> members = ax.classExpressions()
                    .filter(ce -> !ce.isAnonymous())
                    .map(ce -> ce.asOWLClass().getIRI().toString())
                    .collect(Collectors.toList());
            if (members.isEmpty()) {
                return;
            }
            Map<String, Object> axiom = new LinkedHashMap<>();
            String axiomId = "du_" + String.join("_", members.stream().limit(3).toList());
            axiom.put("id", axiomId);
            axiom.put("type", "DisjointUnionOf");
            axiom.put("members", members);
            axiom.put("definition", members.stream()
                    .map(iri -> getLabel(ont, IRI.create(iri)))
                    .collect(Collectors.joining(", ")));
            disjointUnionAxioms.add(axiom);
        });
        details.put("disjointUnionAxioms", disjointUnionAxioms);

        // --- hasKey ---
        List<Map<String, Object>> hasKeyAxioms = new ArrayList<>();
        for (OWLHasKeyAxiom ax : ont.getAxioms(AxiomType.HAS_KEY, imp)) {
            if (!ax.getClassExpression().equals(cls)) {
                continue;
            }
            List<String> keyProperties = new ArrayList<>();
            ax.objectPropertyExpressions()
                    .filter(p -> !p.isAnonymous())
                    .map(p -> p.asOWLObjectProperty().getIRI().toString())
                    .forEach(keyProperties::add);
            ax.dataPropertyExpressions()
                    .map(OWLDataPropertyExpression::asOWLDataProperty)
                    .map(OWLDataProperty::getIRI)
                    .map(IRI::toString)
                    .forEach(keyProperties::add);
            if (keyProperties.isEmpty()) {
                continue;
            }
            Map<String, Object> axiom = new LinkedHashMap<>();
            axiom.put("id", "hasKey_props_" + String.join(",", keyProperties));
            axiom.put("type", "HasKey");
            axiom.put("properties", keyProperties);
            axiom.put("definition", keyProperties.stream()
                    .map(iri -> getLabel(ont, IRI.create(iri)))
                    .collect(Collectors.joining(", ")));
            hasKeyAxioms.add(axiom);
        }
        details.put("hasKeyAxioms", hasKeyAxioms);

        // --- GCIs involving this class as superclass ---
        List<Map<String, String>> generalClassAxioms = new ArrayList<>();
        int gciLimit = 200;
        for (OWLSubClassOfAxiom ax : ont.getAxioms(AxiomType.SUBCLASS_OF, imp)) {
            if (!ax.getSubClass().isAnonymous()) {
                continue;
            }
            OWLClassExpression superCls = ax.getSuperClass();
            if (!superCls.equals(cls) && !expressionReferencesClass(ax.getSubClass(), cls)) {
                continue;
            }
            if (generalClassAxioms.size() >= gciLimit) {
                break;
            }
            String subId = "gci_" + generalClassAxioms.size();
            Map<String, String> gci = new LinkedHashMap<>();
            gci.put("id", subId);
            gci.put("type", "GCI");
            gci.put("definition", classExpressionToManchester(ont, ax.getSubClass()) + " SubClassOf "
                    + classExpressionToManchester(ont, superCls));
            generalClassAxioms.add(gci);
        }
        details.put("generalClassAxioms", generalClassAxioms);

        // --- Anonymous ancestor axioms (named ancestors with anonymous supers) ---
        List<Map<String, String>> anonymousAncestorAxioms = new ArrayList<>();
        Set<String> seenAncestorKeys = new LinkedHashSet<>();
        Deque<OWLClass> queue = new ArrayDeque<>();
        Set<OWLClass> visited = new HashSet<>();
        queue.add(cls);
        visited.add(cls);
        int ancestorSteps = 0;
        final int maxAncestorSteps = 500;
        while (!queue.isEmpty() && ancestorSteps < maxAncestorSteps) {
            OWLClass current = queue.poll();
            ancestorSteps++;
            ont.subClassAxiomsForSubClass(current).forEach(ax -> {
                OWLClassExpression superCe = ax.getSuperClass();
                if (superCe.isAnonymous()) {
                    String superKey = superCe.toString();
                    String ancestorKey = current.getIRI() + "|" + superKey;
                    if (seenAncestorKeys.add(ancestorKey)) {
                        Map<String, String> entry = new LinkedHashMap<>();
                        entry.put("id", superKey);
                        entry.put("type", "SubClassOf");
                        entry.put("ancestorIri", current.getIRI().toString());
                        entry.put("navigable", "false");
                        String manchester = classExpressionToManchester(ont, superCe);
                        entry.put("manchester", manchester);
                        entry.put("definition", manchester);
                        anonymousAncestorAxioms.add(entry);
                    }
                } else if (!superCe.isOWLThing() && !superCe.isOWLNothing()) {
                    OWLClass superNamed = superCe.asOWLClass();
                    if (visited.add(superNamed)) {
                        queue.add(superNamed);
                    }
                }
            });
        }
        details.put("anonymousAncestorAxioms", anonymousAncestorAxioms);

        // --- Structural-reasoner inferred axioms (only when a reasoner is already cached) ---
        if (reasoner != null) {
            Set<OWLClassExpression> assertedEquiv = ont.equivalentClassesAxioms(cls)
                    .flatMap(ax -> ax.classExpressions())
                    .collect(Collectors.toSet());
            Set<OWLClassExpression> assertedSupers = ont.subClassAxiomsForSubClass(cls)
                    .map(OWLSubClassOfAxiom::getSuperClass)
                    .collect(Collectors.toSet());
            Set<OWLClassExpression> assertedDisjoint = ont.disjointClassesAxioms(cls)
                    .flatMap(ax -> ax.classExpressions())
                    .collect(Collectors.toSet());

            try {
                List<Map<String, String>> inferredEquiv = reasoner.getEquivalentClasses(cls).entities()
                        .filter(c -> !c.equals(cls))
                        .filter(c -> !assertedEquiv.contains(c))
                        .map(c -> inferredAxiomMap(c.getIRI().toString(), "EquivalentTo", getLabel(ont, c.getIRI())))
                        .collect(Collectors.toList());
                details.put("inferredEquivalentClassesAxioms", inferredEquiv);

                List<Map<String, String>> inferredSupers = reasoner.getSuperClasses(cls, false).entities()
                        .filter(c -> !c.isOWLThing() && !c.equals(cls))
                        .filter(c -> !assertedSupers.contains(c))
                        .map(c -> inferredAxiomMap(c.getIRI().toString(), "SubClassOf", getLabel(ont, c.getIRI())))
                        .collect(Collectors.toList());
                details.put("inferredSubClassOfAxioms", inferredSupers);

                List<Map<String, String>> inferredDisjoint = reasoner.getDisjointClasses(cls).entities()
                        .filter(c -> !c.equals(cls))
                        .filter(c -> !assertedDisjoint.contains(c))
                        .map(c -> inferredAxiomMap(c.getIRI().toString(), "DisjointWith", getLabel(ont, c.getIRI())))
                        .collect(Collectors.toList());
                details.put("inferredDisjointClassesAxioms", inferredDisjoint);
            } catch (Exception e) {
                log.debug("[Desktop] Structural reasoner inferred supplements failed for {}: {}", classIri, e.getMessage());
                details.putIfAbsent("inferredEquivalentClassesAxioms", List.of());
                details.putIfAbsent("inferredSubClassOfAxioms", List.of());
                details.putIfAbsent("inferredDisjointClassesAxioms", List.of());
            }
        } else {
            details.putIfAbsent("inferredEquivalentClassesAxioms", List.of());
            details.putIfAbsent("inferredSubClassOfAxioms", List.of());
            details.putIfAbsent("inferredDisjointClassesAxioms", List.of());
        }

        log.debug("[Desktop] supplementClassDetails({}) in {}ms", classIri, System.currentTimeMillis() - start);
    }

    private static boolean expressionReferencesClass(OWLClassExpression expr, OWLClass target) {
        return OwlApiQuerySupport.expressionReferencesClass(expr, target);
    }

    private static Map<String, String> inferredAxiomMap(String iri, String type, String definition) {
        Map<String, String> axiom = new LinkedHashMap<>();
        axiom.put("id", iri);
        axiom.put("type", type);
        axiom.put("definition", definition);
        axiom.put("isInferred", "true");
        return axiom;
    }

    private Map<String, Object> classExpressionToAxiomMap(OWLOntology ont, OWLClassExpression ce, String idPrefix) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("definition", classExpressionToManchester(ont, ce));
        if (!ce.isAnonymous()) {
            m.put("id", ce.asOWLClass().getIRI().toString());
            m.put("isRestriction", false);
        } else if (ce instanceof OWLObjectSomeValuesFrom) {
            OWLObjectSomeValuesFrom r = (OWLObjectSomeValuesFrom) ce;
            if (!r.getProperty().isAnonymous()) {
                String pIri = r.getProperty().asOWLObjectProperty().getIRI().toString();
                String fIri = r.getFiller().isAnonymous() ? "" : r.getFiller().asOWLClass().getIRI().toString();
                m.put("id", pIri + "_some_" + fIri); m.put("isRestriction", true);
                m.put("propertyIri", pIri); m.put("restrictionType", "some"); m.put("fillerIri", fIri);
            } else { m.put("id", idPrefix); m.put("isRestriction", true); }
        } else if (ce instanceof OWLObjectAllValuesFrom) {
            OWLObjectAllValuesFrom r = (OWLObjectAllValuesFrom) ce;
            if (!r.getProperty().isAnonymous()) {
                String pIri = r.getProperty().asOWLObjectProperty().getIRI().toString();
                String fIri = r.getFiller().isAnonymous() ? "" : r.getFiller().asOWLClass().getIRI().toString();
                m.put("id", pIri + "_only_" + fIri); m.put("isRestriction", true);
                m.put("propertyIri", pIri); m.put("restrictionType", "only"); m.put("fillerIri", fIri);
            } else { m.put("id", idPrefix); m.put("isRestriction", true); }
        } else if (ce instanceof OWLObjectMinCardinality) {
            OWLObjectMinCardinality r = (OWLObjectMinCardinality) ce;
            if (!r.getProperty().isAnonymous()) {
                String pIri = r.getProperty().asOWLObjectProperty().getIRI().toString();
                String fIri = (!r.getFiller().isAnonymous() && r.getFiller() instanceof OWLClass) ? r.getFiller().asOWLClass().getIRI().toString() : "";
                m.put("id", pIri + "_min" + r.getCardinality() + "_" + fIri); m.put("isRestriction", true);
                m.put("propertyIri", pIri); m.put("restrictionType", "min"); m.put("fillerIri", fIri); m.put("cardinality", r.getCardinality());
            } else { m.put("id", idPrefix); m.put("isRestriction", true); }
        } else if (ce instanceof OWLObjectMaxCardinality) {
            OWLObjectMaxCardinality r = (OWLObjectMaxCardinality) ce;
            if (!r.getProperty().isAnonymous()) {
                String pIri = r.getProperty().asOWLObjectProperty().getIRI().toString();
                String fIri = (!r.getFiller().isAnonymous() && r.getFiller() instanceof OWLClass) ? r.getFiller().asOWLClass().getIRI().toString() : "";
                m.put("id", pIri + "_max" + r.getCardinality() + "_" + fIri); m.put("isRestriction", true);
                m.put("propertyIri", pIri); m.put("restrictionType", "max"); m.put("fillerIri", fIri); m.put("cardinality", r.getCardinality());
            } else { m.put("id", idPrefix); m.put("isRestriction", true); }
        } else if (ce instanceof OWLObjectExactCardinality) {
            OWLObjectExactCardinality r = (OWLObjectExactCardinality) ce;
            if (!r.getProperty().isAnonymous()) {
                String pIri = r.getProperty().asOWLObjectProperty().getIRI().toString();
                String fIri = (!r.getFiller().isAnonymous() && r.getFiller() instanceof OWLClass) ? r.getFiller().asOWLClass().getIRI().toString() : "";
                m.put("id", pIri + "_exactly" + r.getCardinality() + "_" + fIri); m.put("isRestriction", true);
                m.put("propertyIri", pIri); m.put("restrictionType", "exactly"); m.put("fillerIri", fIri); m.put("cardinality", r.getCardinality());
            } else { m.put("id", idPrefix); m.put("isRestriction", true); }
        } else if (ce instanceof OWLObjectHasValue) {
            OWLObjectHasValue r = (OWLObjectHasValue) ce;
            if (!r.getProperty().isAnonymous()) {
                String pIri = r.getProperty().asOWLObjectProperty().getIRI().toString();
                String fIri = r.getFiller().isAnonymous() ? "" : r.getFiller().asOWLNamedIndividual().getIRI().toString();
                m.put("id", pIri + "_value_" + fIri); m.put("isRestriction", true);
                m.put("propertyIri", pIri); m.put("restrictionType", "value"); m.put("fillerIri", fIri);
            } else { m.put("id", idPrefix); m.put("isRestriction", true); }
        } else if (ce instanceof OWLDataSomeValuesFrom) {
            OWLDataSomeValuesFrom r = (OWLDataSomeValuesFrom) ce;
            String pIri = (r.getProperty() instanceof OWLDataProperty) ? ((OWLDataProperty) r.getProperty()).getIRI().toString() : "";
            String fStr = dataRangeToString(r.getFiller());
            String fIri = dataRangeToFullIri(r.getFiller());
            m.put("id", pIri + "_data_some_" + fStr); m.put("isRestriction", true);
            m.put("propertyIri", pIri); m.put("restrictionType", "some"); m.put("fillerIri", fIri);
        } else if (ce instanceof OWLDataAllValuesFrom) {
            OWLDataAllValuesFrom r = (OWLDataAllValuesFrom) ce;
            String pIri = (r.getProperty() instanceof OWLDataProperty) ? ((OWLDataProperty) r.getProperty()).getIRI().toString() : "";
            String fStr = dataRangeToString(r.getFiller());
            String fIri = dataRangeToFullIri(r.getFiller());
            m.put("id", pIri + "_data_only_" + fStr); m.put("isRestriction", true);
            m.put("propertyIri", pIri); m.put("restrictionType", "only"); m.put("fillerIri", fIri);
        } else if (ce instanceof OWLDataMinCardinality) {
            OWLDataMinCardinality r = (OWLDataMinCardinality) ce;
            String pIri = (r.getProperty() instanceof OWLDataProperty) ? ((OWLDataProperty) r.getProperty()).getIRI().toString() : "";
            String fStr = dataRangeToString(r.getFiller());
            String fIri = dataRangeToFullIri(r.getFiller());
            m.put("id", pIri + "_data_min" + r.getCardinality() + "_" + fStr); m.put("isRestriction", true);
            m.put("propertyIri", pIri); m.put("restrictionType", "min"); m.put("fillerIri", fIri); m.put("cardinality", r.getCardinality());
        } else if (ce instanceof OWLDataMaxCardinality) {
            OWLDataMaxCardinality r = (OWLDataMaxCardinality) ce;
            String pIri = (r.getProperty() instanceof OWLDataProperty) ? ((OWLDataProperty) r.getProperty()).getIRI().toString() : "";
            String fStr = dataRangeToString(r.getFiller());
            String fIri = dataRangeToFullIri(r.getFiller());
            m.put("id", pIri + "_data_max" + r.getCardinality() + "_" + fStr); m.put("isRestriction", true);
            m.put("propertyIri", pIri); m.put("restrictionType", "max"); m.put("fillerIri", fIri); m.put("cardinality", r.getCardinality());
        } else if (ce instanceof OWLDataExactCardinality) {
            OWLDataExactCardinality r = (OWLDataExactCardinality) ce;
            String pIri = (r.getProperty() instanceof OWLDataProperty) ? ((OWLDataProperty) r.getProperty()).getIRI().toString() : "";
            String fStr = dataRangeToString(r.getFiller());
            String fIri = dataRangeToFullIri(r.getFiller());
            m.put("id", pIri + "_data_exactly" + r.getCardinality() + "_" + fStr); m.put("isRestriction", true);
            m.put("propertyIri", pIri); m.put("restrictionType", "exactly"); m.put("fillerIri", fIri); m.put("cardinality", r.getCardinality());
        } else if (ce instanceof OWLDataHasValue) {
            OWLDataHasValue r = (OWLDataHasValue) ce;
            String pIri = (r.getProperty() instanceof OWLDataProperty) ? ((OWLDataProperty) r.getProperty()).getIRI().toString() : "";
            String fStr = r.getFiller().getLiteral();
            m.put("id", pIri + "_data_value_" + fStr); m.put("isRestriction", true);
            m.put("propertyIri", pIri); m.put("restrictionType", "value"); m.put("fillerIri", fStr);
        } else {
            // Complex: intersection, union, complement, oneOf
            m.put("id", idPrefix); m.put("isRestriction", false); m.put("isComplex", true);
        }
        return m;
    }

    private String classExpressionToManchester(OWLOntology ont, OWLClassExpression ce) {
        return OwlApiQuerySupport.classExpressionToManchester(ont, ce);
    }

    private String dataRangeToString(OWLDataRange range) {
        return OwlApiQuerySupport.dataRangeToString(range);
    }

    private String dataRangeToFullIri(OWLDataRange range) {
        if (range instanceof OWLDatatype) {
            return ((OWLDatatype) range).getIRI().toString();
        }
        return "";
    }

    private Map<String, Map<String, Integer>> buildClassInstanceCounts(OWLOntology ont) {
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        for (OWLClassAssertionAxiom ax : ont.getAxioms(org.semanticweb.owlapi.model.AxiomType.CLASS_ASSERTION)) {
            OWLIndividual ind = ax.getIndividual();
            OWLClassExpression ce = ax.getClassExpression();
            if (!ind.isNamed() || ce.isAnonymous()) {
                continue;
            }
            OWLClass cls = ce.asOWLClass();
            if (cls.isOWLThing() || cls.isOWLNothing()) {
                continue;
            }
            String classIri = cls.getIRI().toString();
            Map<String, Integer> entry = counts.computeIfAbsent(classIri, k -> {
                Map<String, Integer> m = new LinkedHashMap<>();
                m.put("direct", 0);
                m.put("inferred", 0);
                m.put("total", 0);
                return m;
            });
            entry.put("direct", entry.get("direct") + 1);
            entry.put("total", entry.get("total") + 1);
        }
        return counts;
    }

    private List<Map<String, Object>> buildClassInstances(OWLOntology ont, OWLReasoner reasoner, String classIri) {
        long start = System.currentTimeMillis();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass cls = df.getOWLClass(IRI.create(classIri));
        Set<String> assertedIris = new LinkedHashSet<>();
        List<Map<String, Object>> instances = new ArrayList<>();

        for (OWLClassAssertionAxiom ax : ont.getClassAssertionAxioms(cls)) {
            OWLIndividual ind = ax.getIndividual();
            if (!ind.isNamed()) {
                continue;
            }
            String iri = ind.asOWLNamedIndividual().getIRI().toString();
            if (!assertedIris.add(iri)) {
                continue;
            }
            instances.add(individualInstanceEntry(ont, ind.asOWLNamedIndividual(), classIri, false));
        }

        OWLReasoner r = reasoner;
        if (reasonerService != null) {
            r = reasonerService.findCachedReasoner(ont).orElse(reasoner);
        }
        if (r != null) {
            try {
                for (OWLNamedIndividual ind : r.getInstances(cls, false).getFlattened()) {
                    String iri = ind.getIRI().toString();
                    if (assertedIris.contains(iri)) {
                        continue;
                    }
                    instances.add(individualInstanceEntry(ont, ind, classIri, true));
                }
            } catch (Exception e) {
                log.debug("[Desktop] Reasoner class instances failed for {}: {}", classIri, e.getMessage());
            }
        }

        instances.sort(Comparator.comparing(m -> (String) m.get("label"), String.CASE_INSENSITIVE_ORDER));
        log.info("[PERF][Desktop] classInstances({}) loaded {} in {}ms",
                classIri, instances.size(), System.currentTimeMillis() - start);
        return instances;
    }

    private Map<String, Object> individualInstanceEntry(OWLOntology ont,
                                                        OWLNamedIndividual ind,
                                                        String classIri,
                                                        boolean inferred) {
        String iri = ind.getIRI().toString();
        Map<String, Object> individual = new LinkedHashMap<>();
        individual.put("id", iri);
        individual.put("label", getLabel(ont, ind.getIRI()));
        individual.put("isInferred", inferred);
        individual.put("types", List.of(classIri));
        return individual;
    }

    /**
     * usage: covers every axiom type that references this class.
     * Uses EntitySearcher.getReferencingAxioms equivalent via OWLOntology.referencingAxioms()
     * which is an O(1) HashMap lookup in OWLAPI — milliseconds even on Mondo (3.1M triples).
     */
    private Map<String, Object> buildClassUsage(OWLOntology ont, String classIri) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass cls = df.getOWLClass(IRI.create(classIri));

        List<Map<String, String>> superClasses = new ArrayList<>();
        List<Map<String, String>> subClasses = new ArrayList<>();
        List<Map<String, String>> equivalentClasses = new ArrayList<>();
        List<Map<String, String>> disjointClasses = new ArrayList<>();
        List<Map<String, String>> restrictions = new ArrayList<>();
        List<Map<String, String>> domainOf = new ArrayList<>();
        List<Map<String, String>> rangeOf = new ArrayList<>();
        List<Map<String, String>> instances = new ArrayList<>();
        List<Map<String, String>> annotations = new ArrayList<>();

        // referencingAxioms is O(1) — OWLAPI indexes entity→axioms in a HashMap at load time
        ont.referencingAxioms(cls, org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED).forEach(axiom -> {
            if (axiom instanceof OWLSubClassOfAxiom ax) {
                if (ax.getSubClass().equals(cls) && !ax.getSuperClass().isAnonymous()) {
                    superClasses.add(labeledEntry(ont, ax.getSuperClass().asOWLClass()));
                } else if (ax.getSuperClass().equals(cls) && !ax.getSubClass().isAnonymous()) {
                    subClasses.add(labeledEntry(ont, ax.getSubClass().asOWLClass()));
                } else if (!ax.getSubClass().equals(cls) && !ax.getSubClass().isAnonymous()) {
                    // cls appears inside the superclass restriction expression; subclass is the owning class
                    restrictions.add(usageEntry("SubClassOf restriction", ax.getSubClass().asOWLClass(), ont, ax.toString()));
                }
            } else if (axiom instanceof OWLEquivalentClassesAxiom ax) {
                ax.classExpressions()
                    .filter(ce -> !ce.isAnonymous() && !ce.asOWLClass().equals(cls))
                    .forEach(ce -> equivalentClasses.add(labeledEntry(ont, ce.asOWLClass())));
            } else if (axiom instanceof OWLDisjointClassesAxiom ax) {
                ax.classExpressions()
                    .filter(ce -> !ce.isAnonymous() && !ce.asOWLClass().equals(cls))
                    .forEach(ce -> disjointClasses.add(labeledEntry(ont, ce.asOWLClass())));
            } else if (axiom instanceof OWLObjectPropertyDomainAxiom ax) {
                if (!ax.getProperty().isAnonymous()) {
                    domainOf.add(labeledEntry(ont, ax.getProperty().asOWLObjectProperty()));
                }
            } else if (axiom instanceof OWLObjectPropertyRangeAxiom ax) {
                if (!ax.getProperty().isAnonymous()) {
                    rangeOf.add(labeledEntry(ont, ax.getProperty().asOWLObjectProperty()));
                }
            } else if (axiom instanceof OWLClassAssertionAxiom ax) {
                if (!ax.getIndividual().isAnonymous()) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("iri", ax.getIndividual().asOWLNamedIndividual().getIRI().toString());
                    m.put("label", getLabel(ont, ax.getIndividual().asOWLNamedIndividual().getIRI()));
                    instances.add(m);
                }
            } else if (axiom instanceof OWLAnnotationAssertionAxiom ax) {
                if (ax.getValue().isIRI() && ax.getValue().asIRI()
                        .filter(v -> v.toString().equals(classIri)).isPresent()) {
                    if (ax.getSubject() instanceof IRI subjectIri) {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("iri", subjectIri.toString());
                        m.put("label", getLabel(ont, subjectIri));
                        m.put("property", ax.getProperty().getIRI().getShortForm());
                        annotations.add(m);
                    }
                }
            }
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("superClasses", superClasses);
        result.put("subClasses", subClasses);
        result.put("equivalentClasses", equivalentClasses);
        result.put("disjointClasses", disjointClasses);
        result.put("restrictions", restrictions);
        result.put("domainOf", domainOf);
        result.put("rangeOf", rangeOf);
        result.put("instances", instances);
        result.put("annotations", annotations);
        return result;
    }

    private Map<String, String> usageEntry(String context, OWLClass owner, OWLOntology ont, String rawAxiom) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("iri", owner.getIRI().toString());
        m.put("label", getLabel(ont, owner.getIRI()));
        m.put("context", context);
        return m;
    }

    private Map<String, String> labeledEntry(OWLOntology ont, OWLObjectProperty prop) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("iri", prop.getIRI().toString());
        m.put("label", getLabel(ont, prop.getIRI()));
        return m;
    }

    private Map<String, String> labeledEntry(OWLOntology ont, OWLClass cls) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("iri", cls.getIRI().toString());
        m.put("label", cls.getIRI().getShortForm());
        return m;
    }
}
