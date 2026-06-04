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

    public List<OntologyDto.TreeNode> buildTopLevel(OWLOntology ont, OWLReasoner reasoner, int limit) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        return reasoner
                .getSubClasses(df.getOWLThing(), true)
                .entities()
                .filter(c -> !c.isOWLNothing() && !c.isAnonymous())
                .filter(c -> !hasNamedSuperclassViaReasoner(reasoner, df, c))
                .sorted(Comparator.comparing(c -> getLabel(ont, c).toLowerCase(Locale.ROOT)))
                .limit(Math.max(1, limit))
                .map(c -> toTreeNode(ont, reasoner, c, null))
                .collect(Collectors.toList());
    }

    public List<OntologyDto.TreeNode> buildChildren(OWLOntology ont, OWLReasoner reasoner,
                                                    String parentIri, int limit, int offset) {
        OWLDataFactory df = ont.getOWLOntologyManager().getOWLDataFactory();
        OWLClass parent = df.getOWLClass(IRI.create(parentIri));
        return ont.subClassAxiomsForSuperClass(parent)
                .map(ax -> ax.getSubClass())
                .filter(ce -> !ce.isAnonymous() && !ce.isOWLNothing())
                .map(OWLClassExpression::asOWLClass)
                .distinct()
                .sorted(Comparator.comparing(c -> getLabel(ont, c).toLowerCase(Locale.ROOT)))
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .map(c -> toTreeNode(ont, reasoner, c, parentIri))
                .collect(Collectors.toList());
    }

    /**
     * Precomputes direct children for every named class parent (asserted axioms only).
     */
    public Map<String, List<OntologyDto.TreeNode>> buildChildrenIndex(OWLOntology ont, OWLReasoner reasoner) {
        Map<String, List<OWLClass>> raw = new HashMap<>();
        for (OWLSubClassOfAxiom ax : ont.getAxioms(AxiomType.SUBCLASS_OF)) {
            OWLClassExpression sub = ax.getSubClass();
            OWLClassExpression sup = ax.getSuperClass();
            if (sub.isAnonymous() || sub.isOWLNothing() || sup.isAnonymous() || sup.isOWLNothing()) {
                continue;
            }
            String parentIri = sup.asOWLClass().getIRI().toString();
            raw.computeIfAbsent(parentIri, k -> new ArrayList<>()).add(sub.asOWLClass());
        }

        Map<String, List<OntologyDto.TreeNode>> index = new HashMap<>();
        for (Map.Entry<String, List<OWLClass>> e : raw.entrySet()) {
            String parentIri = e.getKey();
            List<OntologyDto.TreeNode> nodes = e.getValue().stream()
                    .distinct()
                    .sorted(Comparator.comparing(c -> getLabel(ont, c).toLowerCase(Locale.ROOT)))
                    .map(c -> toTreeNode(ont, reasoner, c, parentIri))
                    .collect(Collectors.toList());
            index.put(parentIri, nodes);
        }
        return index;
    }

    private boolean hasNamedSuperclassViaReasoner(OWLReasoner reasoner, OWLDataFactory df, OWLClass cls) {
        return reasoner.getSuperClasses(cls, true)
                .entities()
                .anyMatch(sc -> !sc.isOWLThing() && !sc.isOWLNothing() && !sc.isAnonymous());
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
        return ont.subClassAxiomsForSuperClass(cls)
                .anyMatch(ax -> !ax.getSubClass().isAnonymous()
                        && !ax.getSubClass().isOWLNothing()
                        && !ax.getSubClass().equals(cls));
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
