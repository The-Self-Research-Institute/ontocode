package self.research.ontology.owlEditor.hierarchy;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.dto.OntologyDto;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Protégé-parity asserted class hierarchy (structural reasoner for top-level,
 * explicit subClassOf axioms for children). Shared by desktop warm path and cloud snapshots.
 */
@Component
public class HierarchySnapshotBuilder {

    public int countTopLevelCandidates(OWLOntology ont, OWLReasoner reasoner) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        return (int) reasoner
                .getSubClasses(df.getOWLThing(), true)
                .entities()
                .filter(c -> !c.isOWLNothing() && !c.isAnonymous())
                .filter(c -> !hasNamedSuperclassViaReasoner(reasoner, df, c))
                .count();
    }

    public List<OntologyDto.TreeNode> buildTopLevel(OWLOntology ont, OWLReasoner reasoner, int limit, int offset) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        return reasoner
                .getSubClasses(df.getOWLThing(), true)
                .entities()
                .filter(c -> !c.isOWLNothing() && !c.isAnonymous())
                .filter(c -> !hasNamedSuperclassViaReasoner(reasoner, df, c))
                .sorted(Comparator.comparing(c -> getLabel(ont, c).toLowerCase(Locale.ROOT)))
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .map(c -> toTreeNode(ont, reasoner, c, null))
                .collect(Collectors.toList());
    }

    /**
     * Fast-open path: asserted hierarchy roots (Protégé parity without reasoner precompute).
     * A named class is top-level when it has no asserted named superclass other than owl:Thing.
     */
    public int countTopLevelAsserted(OWLOntology ont) {
        return assertedTopLevelCandidates(ont).size();
    }

    public List<OntologyDto.TreeNode> buildTopLevelAsserted(OWLOntology ont, int limit, int offset) {
        return assertedTopLevelCandidates(ont).stream()
                .sorted(Comparator.comparing(c -> getLabel(ont, c).toLowerCase(Locale.ROOT)))
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .map(c -> toTreeNodeAsserted(ont, c, null))
                .collect(Collectors.toList());
    }

    private Set<OWLClass> assertedTopLevelCandidates(OWLOntology ont) {
        Set<OWLClass> roots = new LinkedHashSet<>();
        for (OWLClass cls : ont.getClassesInSignature(Imports.EXCLUDED)) {
            if (cls.isBuiltIn() || cls.isOWLNothing()) {
                continue;
            }
            if (structuralNamedParents(ont, cls).isEmpty()) {
                roots.add(cls);
            }
        }
        return roots;
    }

    public List<OntologyDto.TreeNode> buildChildren(OWLOntology ont, OWLReasoner reasoner,
                                                    String parentIri, int limit, int offset) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass parent = df.getOWLClass(IRI.create(parentIri));
        return collectAssertedChildClasses(ont, parent).stream()
                .sorted(Comparator.comparing(c -> getLabel(ont, c).toLowerCase(Locale.ROOT)))
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .map(c -> toTreeNode(ont, reasoner, c, parentIri))
                .collect(Collectors.toList());
    }

    /**
     * Precomputes direct children for every named class parent (Protégé asserted hierarchy).
     */
    public Map<String, List<OntologyDto.TreeNode>> buildChildrenIndex(OWLOntology ont, OWLReasoner reasoner) {
        Map<String, Set<OWLClass>> raw = new HashMap<>();
        for (OWLClass cls : ont.getClassesInSignature(Imports.EXCLUDED)) {
            if (cls.isBuiltIn() || cls.isOWLNothing()) {
                continue;
            }
            for (OWLClass parent : structuralNamedParents(ont, cls)) {
                raw.computeIfAbsent(parent.getIRI().toString(), k -> new LinkedHashSet<>()).add(cls);
            }
        }

        Map<String, List<OntologyDto.TreeNode>> index = new HashMap<>();
        for (Map.Entry<String, Set<OWLClass>> e : raw.entrySet()) {
            String parentIri = e.getKey();
            List<OntologyDto.TreeNode> nodes = e.getValue().stream()
                    .sorted(Comparator.comparing(c -> getLabel(ont, c).toLowerCase(Locale.ROOT)))
                    .map(c -> toTreeNode(ont, reasoner, c, parentIri))
                    .collect(Collectors.toList());
            index.put(parentIri, nodes);
        }
        return index;
    }

    /**
     * Protégé asserted children: direct subClassOf plus classes defined as equivalent to
     * (Parent ⊓ …) or subClassOf (Parent ⊓ …).
     */
    private Set<OWLClass> collectAssertedChildClasses(OWLOntology ont, OWLClass parent) {
        Set<OWLClass> children = new LinkedHashSet<>();
        ont.subClassAxiomsForSuperClass(parent)
                .map(OWLSubClassOfAxiom::getSubClass)
                .filter(ce -> !ce.isAnonymous() && !ce.isOWLNothing())
                .map(OWLClassExpression::asOWLClass)
                .forEach(children::add);

        for (OWLClass cls : ont.getClassesInSignature(Imports.EXCLUDED)) {
            if (cls.isBuiltIn() || cls.isOWLNothing() || cls.equals(parent)) {
                continue;
            }
            if (structuralNamedParents(ont, cls).contains(parent)) {
                children.add(cls);
            }
        }
        return children;
    }

    /**
     * Named parents in the asserted hierarchy: explicit subClassOf plus named conjuncts from
     * equivalentClass / anonymous subClassOf intersections (e.g. Customer under Person).
     */
    private Set<OWLClass> structuralNamedParents(OWLOntology ont, OWLClass cls) {
        Set<OWLClass> parents = new LinkedHashSet<>();
        for (OWLSubClassOfAxiom ax : ont.subClassAxiomsForSubClass(cls).toList()) {
            OWLClassExpression sup = ax.getSuperClass();
            if (!sup.isAnonymous() && !sup.isOWLThing() && !sup.isOWLNothing()) {
                parents.add(sup.asOWLClass());
            } else if (sup instanceof OWLObjectIntersectionOf) {
                namedConjuncts(sup).stream()
                        .filter(p -> !p.equals(cls) && !p.isOWLThing() && !p.isOWLNothing())
                        .forEach(parents::add);
            }
        }
        for (OWLEquivalentClassesAxiom ax : ont.equivalentClassesAxioms(cls).toList()) {
            for (OWLClassExpression expr : ax.getClassExpressionsAsList()) {
                if (expr.equals(cls) || !(expr instanceof OWLObjectIntersectionOf)) {
                    continue;
                }
                namedConjuncts(expr).stream()
                        .filter(p -> !p.equals(cls) && !p.isOWLThing() && !p.isOWLNothing())
                        .forEach(parents::add);
            }
        }
        return parents;
    }

    private Set<OWLClass> namedConjuncts(OWLClassExpression expr) {
        if (!(expr instanceof OWLObjectIntersectionOf intersection)) {
            return Set.of();
        }
        return intersection.getOperandsAsList().stream()
                .filter(OWLClassExpression::isNamed)
                .map(OWLClassExpression::asOWLClass)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean hasNamedSuperclassViaReasoner(OWLReasoner reasoner, OWLDataFactory df, OWLClass cls) {
        return reasoner.getSuperClasses(cls, true)
                .entities()
                .anyMatch(sc -> !sc.isOWLThing() && !sc.isOWLNothing() && !sc.isAnonymous());
    }

    private OntologyDto.TreeNode toTreeNodeAsserted(OWLOntology ont, OWLClass cls, String parentIri) {
        String iri = cls.getIRI().toString();
        String label = getLabel(ont, cls);
        String description = getAnnotation(ont, cls, "http://purl.obolibrary.org/obo/IAO_0000115");
        if (description == null) {
            description = getAnnotation(ont, cls, "http://www.w3.org/2000/01/rdf-schema#comment");
        }
        boolean hasChildren = hasDirectChildren(ont, cls);
        List<Map<String, String>> equivalentClasses = getEquivalentClasses(ont, cls);

        OntologyDto.TreeNode node = new OntologyDto.TreeNode();
        node.setId(iri);
        node.setLabel(label);
        node.setDescription(description);
        node.setParent(parentIri);
        node.setHasChildren(hasChildren);
        if (!equivalentClasses.isEmpty()) {
            node.setEquivalentClasses(equivalentClasses);
        }
        return node;
    }

    private OntologyDto.TreeNode toTreeNode(OWLOntology ont, OWLReasoner reasoner,
                                            OWLClass cls, String parentIri) {
        String iri = cls.getIRI().toString();
        String label = getLabel(ont, cls);
        String description = getAnnotation(ont, cls, "http://purl.obolibrary.org/obo/IAO_0000115");
        if (description == null) {
            description = getAnnotation(ont, cls, "http://www.w3.org/2000/01/rdf-schema#comment");
        }
        boolean hasChildren = hasDirectChildren(ont, cls);
        List<Map<String, String>> equivalentClasses = getEquivalentClasses(ont, cls);

        OntologyDto.TreeNode node = new OntologyDto.TreeNode();
        node.setId(iri);
        node.setLabel(label);
        node.setDescription(description);
        node.setParent(parentIri);
        node.setHasChildren(hasChildren);
        if (!equivalentClasses.isEmpty()) {
            node.setEquivalentClasses(equivalentClasses);
        }
        return node;
    }

    private boolean hasDirectChildren(OWLOntology ont, OWLClass cls) {
        return !collectAssertedChildClasses(ont, cls).isEmpty();
    }

    private String getLabel(OWLOntology ont, OWLClass cls) {
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
