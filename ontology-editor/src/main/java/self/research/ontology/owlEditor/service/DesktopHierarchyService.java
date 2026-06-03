package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;

import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.dto.OntologyDto;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Desktop-only hierarchy service using OWLAPI in-memory model.
 *
 * Routes to this service when:
 *   1. ontocode.desktop.mode=true
 *   2. ProjectOntologyCache has an entry for the requested project
 *
 * Hierarchy traversal is done via OWLReasoner.getSubClasses(direct=true)
 * using the structural (non-inferencing) reasoner — nanosecond latency,
 * no SPARQL, no network.
 *
 * Cloud mode never activates this bean (ConditionalOnProperty).
 */
@Service
@ConditionalOnProperty(name = "ontocode.desktop.mode", havingValue = "true")
public class DesktopHierarchyService {

    private static final Logger log = LoggerFactory.getLogger(DesktopHierarchyService.class);

    @Autowired
    private ProjectOntologyCache ontologyCache;

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean hasOntology(String projectId) {
        return ontologyCache.has(projectId);
    }

    /**
     * Fast OWLAPI declaration counts for tab badges (no SPARQL).
     * Keys: classCount, objectPropertyCount, dataPropertyCount, individualCount, annotationPropertyCount.
     */
    public Map<String, Object> declarationCounts(String projectId) {
        return ontologyCache.get(projectId)
            .map(c -> buildDeclarationCounts(c.ontology()))
            .orElse(Collections.emptyMap());
    }

    /** Direct subclasses of owl:Thing (top-level class hierarchy). */
    public List<OntologyDto.TreeNode> topLevelClasses(String projectId, int limit) {
        return ontologyCache.get(projectId)
            .map(c -> buildTopLevel(c.ontology(), c.reasoner(), limit))
            .orElse(Collections.emptyList());
    }

    /** Number of direct owl:Thing subclasses returned by {@link #topLevelClasses} before the limit is applied. */
    public int topLevelClassTotal(String projectId) {
        return ontologyCache.get(projectId)
            .map(c -> countTopLevelCandidates(c.ontology(), c.reasoner()))
            .orElse(0);
    }

    /**
     * Full class details — matches the shape returned by OntologyQueryService.classDetails().
     * All computed from OWLAPI in-memory model: zero SPARQL queries, instant response.
     */
    public Map<String, Object> classDetails(String projectId, String classIri) {
        return ontologyCache.get(projectId)
            .map(c -> buildClassDetails(c.ontology(), c.reasoner(), classIri))
            .orElse(Collections.emptyMap());
    }

    private Map<String, Object> buildClassDetails(OWLOntology ont, OWLReasoner reasoner, String classIri) {
        long start = System.currentTimeMillis();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass cls = df.getOWLClass(IRI.create(classIri));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", classIri);

        // ── Annotations ──────────────────────────────────────────────────────
        IRI rdfsLabel = IRI.create("http://www.w3.org/2000/01/rdf-schema#label");
        // Store annotation values as plain strings — same format as OntologyQueryService (Fuseki path).
        // Frontend renders annotations[propIri] as a string; objects cause [object Object] display.
        Map<String, Object> annotations = new LinkedHashMap<>();
        ont.annotationAssertionAxioms(cls.getIRI(), Imports.EXCLUDED).forEach(ax -> {
            String prop = ax.getProperty().getIRI().toString();
            ax.getValue().asLiteral().ifPresent(lit ->
                annotations.putIfAbsent(prop, lit.getLiteral()));
            ax.getValue().asIRI().ifPresent(iri ->
                annotations.putIfAbsent(prop, iri.toString()));
        });
        details.put("annotations", annotations);

        // ── Named superclasses (subClassOf axioms) ────────────────────────────
        List<Map<String, String>> subClassOfAxioms = ont.subClassAxiomsForSubClass(cls)
            .map(ax -> ax.getSuperClass())
            .filter(ce -> !ce.isAnonymous() && !ce.isOWLThing() && !ce.isOWLNothing())
            .map(ce -> labeledEntry(ont, ce.asOWLClass()))
            .collect(Collectors.toList());
        details.put("subClassOfAxioms", subClassOfAxioms);

        // ── Equivalent classes ────────────────────────────────────────────────
        List<Map<String, String>> equivalentClassesAxioms = ont.equivalentClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls) && !ce.isAnonymous())
            .map(ce -> labeledEntry(ont, ce.asOWLClass()))
            .collect(Collectors.toList());
        details.put("equivalentClassesAxioms", equivalentClassesAxioms);

        // ── Disjoint classes ──────────────────────────────────────────────────
        List<Map<String, String>> disjointClassesAxioms = ont.disjointClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls) && !ce.isAnonymous())
            .map(ce -> labeledEntry(ont, ce.asOWLClass()))
            .collect(Collectors.toList());
        details.put("disjointClassesAxioms", disjointClassesAxioms);

        // ── Restrictions (someValues, allValues, hasValue) ────────────────────
        List<Map<String, Object>> restrictions = ont.subClassAxiomsForSubClass(cls)
            .map(ax -> ax.getSuperClass())
            .filter(OWLClassExpression::isAnonymous)
            .flatMap(ce -> extractRestrictions(ont, ce))
            .collect(Collectors.toList());
        details.put("restrictions", restrictions);

        // ── Union-of members (if this class is defined as a union) ────────────
        List<Map<String, String>> unionMembers = ont.equivalentClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls) && ce instanceof org.semanticweb.owlapi.model.OWLObjectUnionOf)
            .flatMap(ce -> ((org.semanticweb.owlapi.model.OWLObjectUnionOf) ce).operands())
            .filter(op -> !op.isAnonymous())
            .map(op -> labeledEntry(ont, op.asOWLClass()))
            .collect(Collectors.toList());
        details.put("unionOfMembers", unionMembers);

        // ── Intersection-of members ────────────────────────────────────────────
        List<Map<String, String>> intersectionMembers = ont.equivalentClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls) && ce instanceof org.semanticweb.owlapi.model.OWLObjectIntersectionOf)
            .flatMap(ce -> ((org.semanticweb.owlapi.model.OWLObjectIntersectionOf) ce).operands())
            .filter(op -> !op.isAnonymous())
            .map(op -> labeledEntry(ont, op.asOWLClass()))
            .collect(Collectors.toList());
        details.put("intersectionOfMembers", intersectionMembers);

        // ── Direct subclasses (children) ──────────────────────────────────────
        List<Map<String, String>> directSubclasses = reasoner.getSubClasses(cls, true)
            .entities()
            .filter(c -> !c.isOWLNothing() && !c.isAnonymous())
            .map(c -> labeledEntry(ont, c))
            .collect(Collectors.toList());
        details.put("directSubclasses", directSubclasses);

        log.debug("[Desktop] classDetails({}) in {}ms", classIri, System.currentTimeMillis() - start);
        return details;
    }

    private Stream<Map<String, Object>> extractRestrictions(OWLOntology ont, OWLClassExpression ce) {
        if (ce instanceof OWLRestriction) {
            OWLRestriction r = (OWLRestriction) ce;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("property", r.getProperty().isAnonymous() ? "" : r.getProperty().asOWLObjectProperty().getIRI().toString());
            m.put("propertyLabel", r.getProperty().isAnonymous() ? "" :
                getLabel(ont, ont.getOWLOntologyManager().getOWLDataFactory().getOWLClass(r.getProperty().asOWLObjectProperty().getIRI())));
            m.put("type", ce.getClassExpressionType().getName());
            if (ce instanceof OWLQuantifiedRestriction) {
                OWLClassExpression filler = ((OWLQuantifiedRestriction<OWLClassExpression>) ce).getFiller();
                if (!filler.isAnonymous()) {
                    m.put("filler", filler.asOWLClass().getIRI().toString());
                    m.put("fillerLabel", getLabel(ont, filler.asOWLClass()));
                }
            }
            return Stream.of(m);
        }
        return Stream.empty();
    }

    /**
     * Find all axioms that reference this class — OWLAPI in-memory, instant.
     * Returns a map with superClasses, equivalentClasses, disjointClasses,
     * domainOf, rangeOf, and subClassAxioms counts.
     */
    public Map<String, Object> classUsage(String projectId, String classIri) {
        return ontologyCache.get(projectId)
            .map(c -> buildClassUsage(c.ontology(), classIri))
            .orElse(Collections.emptyMap());
    }

    private Map<String, Object> buildClassUsage(OWLOntology ont, String classIri) {
        long start = System.currentTimeMillis();
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass cls = df.getOWLClass(IRI.create(classIri));

        // superClasses: axioms where this class is the subclass
        List<Map<String, String>> superClasses = ont.subClassAxiomsForSubClass(cls)
            .map(ax -> ax.getSuperClass())
            .filter(ce -> !ce.isAnonymous())
            .map(ce -> labeledEntry(ont, ce.asOWLClass()))
            .collect(Collectors.toList());

        // subClasses: axioms where this class is the superclass
        List<Map<String, String>> subClasses = ont.subClassAxiomsForSuperClass(cls)
            .map(ax -> ax.getSubClass())
            .filter(ce -> !ce.isAnonymous())
            .map(ce -> labeledEntry(ont, ce.asOWLClass()))
            .collect(Collectors.toList());

        // equivalentClasses
        List<Map<String, String>> equivalentClasses = ont.equivalentClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls) && !ce.isAnonymous())
            .map(ce -> labeledEntry(ont, ce.asOWLClass()))
            .collect(Collectors.toList());

        // disjointWith
        List<Map<String, String>> disjointClasses = ont.disjointClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls) && !ce.isAnonymous())
            .map(ce -> labeledEntry(ont, ce.asOWLClass()))
            .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("superClasses", superClasses);
        result.put("subClasses", subClasses);
        result.put("equivalentClasses", equivalentClasses);
        result.put("disjointClasses", disjointClasses);

        log.debug("[Desktop] classUsage({}) in {}ms", classIri, System.currentTimeMillis() - start);
        return result;
    }

    private Map<String, String> labeledEntry(OWLOntology ont, OWLClass cls) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("iri", cls.getIRI().toString());
        m.put("label", getLabel(ont, cls));
        return m;
    }

    /** Direct subclasses of a named class. */
    public List<OntologyDto.TreeNode> children(String projectId, String parentIri, int limit, int offset) {
        return ontologyCache.get(projectId)
            .map(c -> buildChildren(c.ontology(), c.reasoner(), parentIri, limit, offset))
            .orElse(Collections.emptyList());
    }

    // ── Implementation ────────────────────────────────────────────────────────

    private Map<String, Object> buildDeclarationCounts(OWLOntology ont) {
        Imports imp = Imports.INCLUDED;
        long classCount = ont.classesInSignature(imp).filter(c -> !c.isBuiltIn()).count();
        long objectPropertyCount = ont.objectPropertiesInSignature(imp).filter(p -> !p.isBuiltIn()).count();
        long dataPropertyCount = ont.dataPropertiesInSignature(imp).filter(p -> !p.isBuiltIn()).count();
        long individualCount = ont.individualsInSignature(imp).filter(i -> !i.isBuiltIn()).count();
        long annotationPropertyCount = ont.annotationPropertiesInSignature(imp).filter(p -> !p.isBuiltIn()).count();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("classCount", classCount);
        counts.put("objectPropertyCount", objectPropertyCount);
        counts.put("dataPropertyCount", dataPropertyCount);
        counts.put("individualCount", individualCount);
        counts.put("annotationPropertyCount", annotationPropertyCount);
        return counts;
    }

    private int countTopLevelCandidates(OWLOntology ont, OWLReasoner reasoner) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        return (int) reasoner
            .getSubClasses(df.getOWLThing(), true)
            .entities()
            .filter(c -> !c.isOWLNothing() && !c.isAnonymous())
            .filter(c -> !hasNamedSuperclassViaReasoner(reasoner, df, c))
            .count();
    }

    private List<OntologyDto.TreeNode> buildTopLevel(OWLOntology ont, OWLReasoner reasoner, int limit) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        long start = System.currentTimeMillis();

        List<OntologyDto.TreeNode> result = reasoner
            .getSubClasses(df.getOWLThing(), true)
            .entities()
            .filter(c -> !c.isOWLNothing() && !c.isAnonymous())
            // Exclude classes that have any named superclass via the reasoner
            // (which includes classes that are union members — no explicit axiom needed).
            // Without this, Viruses appears BOTH as union member child AND at root level.
            .filter(c -> !hasNamedSuperclassViaReasoner(reasoner, df, c))
            .limit(limit)
            .map(c -> toTreeNode(ont, reasoner, c, null))
            .collect(Collectors.toList());

        log.debug("[Desktop] topLevelClasses: {} nodes in {}ms", result.size(),
            System.currentTimeMillis() - start);
        return result;
    }

    /**
     * Returns true if the reasoner computes a named superclass other than owl:Thing.
     * Using the reasoner (not just explicit axioms) catches union members:
     * e.g. Viruses has no explicit rdfs:subClassOf to the union class, but the
     * reasoner correctly computes it as a subclass via equivalentClass/unionOf semantics.
     */
    private boolean hasNamedSuperclassViaReasoner(OWLReasoner reasoner, OWLDataFactory df, OWLClass cls) {
        return reasoner.getSuperClasses(cls, true)  // direct=true → immediate parents only
            .entities()
            .anyMatch(sc -> !sc.isOWLThing() && !sc.isOWLNothing() && !sc.isAnonymous());
    }

    private List<OntologyDto.TreeNode> buildChildren(OWLOntology ont, OWLReasoner reasoner,
                                                      String parentIri, int limit, int offset) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass parent = df.getOWLClass(IRI.create(parentIri));
        long start = System.currentTimeMillis();

        // Use EXPLICIT subClassOf axioms only — NOT the reasoner.
        // reasoner.getSubClasses(parent) includes computed union membership:
        //   e.g. "Archaea or Eukaryota" gets Archaea AND Eukaryota as children
        //   even though they have no rdfs:subClassOf to the union class,
        //   causing them to appear duplicated under both parents.
        // ont.subClassAxiomsForSuperClass(parent) returns only declared axioms.
        List<OntologyDto.TreeNode> result = ont.subClassAxiomsForSuperClass(parent)
            .map(ax -> ax.getSubClass())
            .filter(ce -> !ce.isAnonymous() && !ce.isOWLNothing())
            .map(OWLClassExpression::asOWLClass)
            .distinct()
            .skip(offset)
            .limit(limit)
            .map(c -> toTreeNode(ont, reasoner, c, parentIri))
            .collect(Collectors.toList());

        log.debug("[Desktop] children({}) offset={}: {} nodes in {}ms",
            parentIri, offset, result.size(), System.currentTimeMillis() - start);
        return result;
    }

    private OntologyDto.TreeNode toTreeNode(OWLOntology ont, OWLReasoner reasoner,
                                             OWLClass cls, String parentIri) {
        String iri = cls.getIRI().toString();
        String label = getLabel(ont, cls);
        String description = getAnnotation(ont, cls, "http://purl.obolibrary.org/obo/IAO_0000115"); // def
        if (description == null) description = getAnnotation(ont, cls, "http://www.w3.org/2000/01/rdf-schema#comment");

        boolean hasChildren = hasDirectChildren(ont, cls);
        List<Map<String, String>> equivalentClasses = getEquivalentClasses(ont, cls);

        OntologyDto.TreeNode node = new OntologyDto.TreeNode();
        node.setId(iri);
        node.setLabel(label);
        node.setDescription(description);
        node.setParent(parentIri);
        node.setHasChildren(hasChildren);
        if (!equivalentClasses.isEmpty()) node.setEquivalentClasses(equivalentClasses);
        return node;
    }

    private boolean hasDirectChildren(OWLOntology ont, OWLClass cls) {
        // Use explicit axioms only — consistent with buildChildren()
        return ont.subClassAxiomsForSuperClass(cls)
            .anyMatch(ax -> !ax.getSubClass().isAnonymous()
                && !ax.getSubClass().isOWLNothing()
                && !ax.getSubClass().equals(cls));
    }

    private String getLabel(OWLOntology ont, OWLClass cls) {
        // rdfs:label first, then oboInOwl:id, then IRI short form
        IRI rdfsLabel = IRI.create("http://www.w3.org/2000/01/rdf-schema#label");
        Optional<String> label = ont.annotationAssertionAxioms(cls.getIRI(), Imports.EXCLUDED)
            .filter(a -> a.getProperty().getIRI().equals(rdfsLabel))
            .sorted(Comparator.comparing(a -> langPriority(a.getValue())))
            .findFirst()
            .flatMap(a -> a.getValue().asLiteral())
            .map(OWLLiteral::getLiteral);

        return label.orElseGet(() -> cls.getIRI().getShortForm());
    }

    private String getAnnotation(OWLOntology ont, OWLClass cls, String propertyIri) {
        return ont.annotationAssertionAxioms(cls.getIRI(), Imports.EXCLUDED)
            .filter(a -> a.getProperty().getIRI().toString().equals(propertyIri))
            .findFirst()
            .flatMap(a -> a.getValue().asLiteral())
            .map(OWLLiteral::getLiteral)
            .orElse(null);
    }

    /** Prefer English labels; sort "" (no lang) before others. */
    private int langPriority(OWLAnnotationValue v) {
        return v.asLiteral()
            .map(lit -> {
                String lang = lit.getLang();
                if (lang == null || lang.isEmpty()) return 0;
                if (lang.startsWith("en")) return 1;
                return 2;
            }).orElse(3);
    }

    private List<Map<String, String>> getEquivalentClasses(OWLOntology ont, OWLClass cls) {
        return ont.equivalentClassesAxioms(cls)
            .flatMap(ax -> ax.classExpressions())
            .filter(ce -> !ce.equals(cls) && !ce.isAnonymous())
            .map(ce -> {
                OWLClass eq = ce.asOWLClass();
                Map<String, String> m = new LinkedHashMap<>();
                m.put("iri", eq.getIRI().toString());
                m.put("label", getLabel(ont, eq));
                return m;
            })
            .collect(Collectors.toList());
    }
}
