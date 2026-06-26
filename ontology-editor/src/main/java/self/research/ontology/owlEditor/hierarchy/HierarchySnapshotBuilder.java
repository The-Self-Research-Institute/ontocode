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
 *
 * <p>The {@code importsScope} parameter mirrors Protégé's View menu:
 * <ul>
 *   <li>{@link Imports#EXCLUDED} — "Show only the active ontology" (default)</li>
 *   <li>{@link Imports#INCLUDED} — "Show the imports closure of the active ontology"</li>
 * </ul>
 */
@Component
public class HierarchySnapshotBuilder {

    public int countTopLevelCandidates(OWLOntology ont, OWLReasoner reasoner) {
        return countTopLevelCandidates(ont, reasoner, Imports.EXCLUDED);
    }

    public int countTopLevelCandidates(OWLOntology ont, OWLReasoner reasoner, Imports importsScope) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        return (int) reasoner
                .getSubClasses(df.getOWLThing(), true)
                .entities()
                .filter(c -> !c.isOWLNothing() && !c.isAnonymous())
                .filter(c -> importsScope == Imports.INCLUDED || isInActiveOntology(ont, c))
                .filter(c -> !hasNamedSuperclassViaReasoner(reasoner, df, c))
                .count();
    }

    public List<OntologyDto.TreeNode> buildTopLevel(OWLOntology ont, OWLReasoner reasoner, int limit, int offset) {
        return buildTopLevel(ont, reasoner, limit, offset, Imports.EXCLUDED);
    }

    public List<OntologyDto.TreeNode> buildTopLevel(OWLOntology ont, OWLReasoner reasoner, int limit, int offset,
                                                    Imports importsScope) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        return reasoner
                .getSubClasses(df.getOWLThing(), true)
                .entities()
                .filter(c -> !c.isOWLNothing() && !c.isAnonymous())
                .filter(c -> importsScope == Imports.INCLUDED || isInActiveOntology(ont, c))
                .filter(c -> !hasNamedSuperclassViaReasoner(reasoner, df, c))
                .sorted(Comparator.comparing(c -> getLabel(ont, c, importsScope).toLowerCase(Locale.ROOT)))
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .map(c -> toTreeNode(ont, reasoner, c, null, importsScope))
                .collect(Collectors.toList());
    }

    /**
     * Fast-open path: asserted hierarchy roots (Protégé parity without reasoner precompute).
     * A named class is top-level when it has no asserted named superclass other than owl:Thing.
     */
    public int countTopLevelAsserted(OWLOntology ont) {
        return countTopLevelAsserted(ont, Imports.EXCLUDED);
    }

    public int countTopLevelAsserted(OWLOntology ont, Imports importsScope) {
        return assertedTopLevelCandidates(ont, importsScope).size();
    }

    public List<OntologyDto.TreeNode> buildTopLevelAsserted(OWLOntology ont, int limit, int offset) {
        return buildTopLevelAsserted(ont, limit, offset, Imports.EXCLUDED);
    }

    public List<OntologyDto.TreeNode> buildTopLevelAsserted(OWLOntology ont, int limit, int offset,
                                                            Imports importsScope) {
        return assertedTopLevelCandidates(ont, importsScope).stream()
                .sorted(Comparator.comparing(c -> getLabel(ont, c, importsScope).toLowerCase(Locale.ROOT)))
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .map(c -> toTreeNodeAsserted(ont, c, null, importsScope))
                .collect(Collectors.toList());
    }

    private Set<OWLClass> assertedTopLevelCandidates(OWLOntology ont, Imports importsScope) {
        Set<OWLClass> roots = new LinkedHashSet<>();
        for (OWLClass cls : ont.getClassesInSignature(importsScope)) {
            if (cls.isBuiltIn() || cls.isOWLNothing()) {
                continue;
            }
            if (structuralNamedParents(ont, cls, importsScope).isEmpty()) {
                roots.add(cls);
            }
        }
        return roots;
    }

    public List<OntologyDto.TreeNode> buildChildren(OWLOntology ont, OWLReasoner reasoner,
                                                    String parentIri, int limit, int offset) {
        return buildChildren(ont, reasoner, parentIri, limit, offset, Imports.EXCLUDED);
    }

    public List<OntologyDto.TreeNode> buildChildren(OWLOntology ont, OWLReasoner reasoner,
                                                    String parentIri, int limit, int offset,
                                                    Imports importsScope) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass parent = df.getOWLClass(IRI.create(parentIri));
        return collectAssertedChildClasses(ont, parent, importsScope).stream()
                .sorted(Comparator.comparing(c -> getLabel(ont, c, importsScope).toLowerCase(Locale.ROOT)))
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .map(c -> toTreeNode(ont, reasoner, c, parentIri, importsScope))
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
            for (OWLClass parent : structuralNamedParents(ont, cls, Imports.EXCLUDED)) {
                raw.computeIfAbsent(parent.getIRI().toString(), k -> new LinkedHashSet<>()).add(cls);
            }
        }

        Map<String, List<OntologyDto.TreeNode>> index = new HashMap<>();
        for (Map.Entry<String, Set<OWLClass>> e : raw.entrySet()) {
            String parentIri = e.getKey();
            List<OntologyDto.TreeNode> nodes = e.getValue().stream()
                    .sorted(Comparator.comparing(c -> getLabel(ont, c, Imports.EXCLUDED).toLowerCase(Locale.ROOT)))
                    .map(c -> toTreeNode(ont, reasoner, c, parentIri, Imports.EXCLUDED))
                    .collect(Collectors.toList());
            index.put(parentIri, nodes);
        }
        return index;
    }

    /**
     * Protégé asserted children: direct subClassOf plus classes defined as equivalent to
     * (Parent ⊓ …) or subClassOf (Parent ⊓ …).
     */
    private Set<OWLClass> collectAssertedChildClasses(OWLOntology ont, OWLClass parent, Imports importsScope) {
        Set<OWLClass> children = new LinkedHashSet<>();
        ont.subClassAxiomsForSuperClass(parent)
                .map(OWLSubClassOfAxiom::getSubClass)
                .filter(ce -> !ce.isAnonymous() && !ce.isOWLNothing())
                .map(OWLClassExpression::asOWLClass)
                .forEach(children::add);

        for (OWLClass cls : ont.getClassesInSignature(importsScope)) {
            if (cls.isBuiltIn() || cls.isOWLNothing() || cls.equals(parent)) {
                continue;
            }
            if (structuralNamedParents(ont, cls, importsScope).contains(parent)) {
                children.add(cls);
            }
        }
        return children;
    }

    /** Returns true when the class is declared in the active (non-imported) ontology. */
    private boolean isInActiveOntology(OWLOntology ont, OWLClass cls) {
        return ont.containsClassInSignature(cls.getIRI(), Imports.EXCLUDED);
    }

    /**
     * Returns the IRI of the imported ontology that first declares this class,
     * or null if the class belongs to the active ontology.
     */
    private String findSourceOntologyIri(OWLOntology ont, OWLClass cls) {
        if (isInActiveOntology(ont, cls)) return null;
        for (OWLOntology imported : ont.importsClosure().toList()) {
            if (imported.equals(ont)) continue;
            if (imported.containsClassInSignature(cls.getIRI(), Imports.EXCLUDED)) {
                return imported.getOntologyID().getOntologyIRI()
                        .map(IRI::toString).orElse(imported.getOntologyID().toString());
            }
        }
        return null;
    }

    /**
     * Named parents in the asserted hierarchy: explicit subClassOf plus named conjuncts from
     * equivalentClass / anonymous subClassOf intersections (e.g. Customer under Person).
     */
    private Set<OWLClass> structuralNamedParents(OWLOntology ont, OWLClass cls, Imports importsScope) {
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

    private OntologyDto.TreeNode toTreeNodeAsserted(OWLOntology ont, OWLClass cls, String parentIri,
                                                    Imports importsScope) {
        String iri = cls.getIRI().toString();
        String label = getLabel(ont, cls, importsScope);
        String description = getAnnotation(ont, cls, "http://purl.obolibrary.org/obo/IAO_0000115", importsScope);
        if (description == null) {
            description = getAnnotation(ont, cls, "http://www.w3.org/2000/01/rdf-schema#comment", importsScope);
        }
        boolean hasChildren = hasDirectChildren(ont, cls, importsScope);
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
        if (importsScope == Imports.INCLUDED) {
            node.setSourceOntology(findSourceOntologyIri(ont, cls));
        }
        return node;
    }

    private OntologyDto.TreeNode toTreeNode(OWLOntology ont, OWLReasoner reasoner,
                                            OWLClass cls, String parentIri, Imports importsScope) {
        String iri = cls.getIRI().toString();
        String label = getLabel(ont, cls, importsScope);
        String description = getAnnotation(ont, cls, "http://purl.obolibrary.org/obo/IAO_0000115", importsScope);
        if (description == null) {
            description = getAnnotation(ont, cls, "http://www.w3.org/2000/01/rdf-schema#comment", importsScope);
        }
        boolean hasChildren = hasDirectChildren(ont, cls, importsScope);
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
        if (importsScope == Imports.INCLUDED) {
            node.setSourceOntology(findSourceOntologyIri(ont, cls));
        }
        return node;
    }

    private boolean hasDirectChildren(OWLOntology ont, OWLClass cls, Imports importsScope) {
        return !collectAssertedChildClasses(ont, cls, importsScope).isEmpty();
    }

    private String getLabel(OWLOntology ont, OWLClass cls, Imports importsScope) {
        IRI rdfsLabel = IRI.create("http://www.w3.org/2000/01/rdf-schema#label");
        Optional<String> label = ont.annotationAssertionAxioms(cls.getIRI(), importsScope)
                .filter(a -> a.getProperty().getIRI().equals(rdfsLabel))
                .sorted(Comparator.comparing(a -> langPriority(a.getValue())))
                .findFirst()
                .flatMap(a -> a.getValue().asLiteral())
                .map(OWLLiteral::getLiteral);
        return label.orElseGet(() -> cls.getIRI().getShortForm());
    }

    private String getAnnotation(OWLOntology ont, OWLClass cls, String propertyIri, Imports importsScope) {
        return ont.annotationAssertionAxioms(cls.getIRI(), importsScope)
                .filter(a -> a.getProperty().getIRI().toString().equals(propertyIri))
                .findFirst()
                .flatMap(a -> a.getValue().asLiteral())
                .map(OWLLiteral::getLiteral)
                .orElse(null);
    }

    /**
     * Returns the first value of a specific annotation property for the given class IRI.
     * Used by the batch annotation endpoint.
     */
    public String getAnnotationValue(OWLOntology ont, String classIri, String propertyIri) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass cls = df.getOWLClass(IRI.create(classIri));
        return ont.annotationAssertionAxioms(cls.getIRI(), Imports.INCLUDED)
                .filter(a -> a.getProperty().getIRI().toString().equals(propertyIri))
                .findFirst()
                .map(a -> {
                    if (a.getValue().asLiteral().isPresent()) {
                        return a.getValue().asLiteral().get().getLiteral();
                    }
                    return a.getValue().asIRI().map(IRI::toString).orElse(null);
                })
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
