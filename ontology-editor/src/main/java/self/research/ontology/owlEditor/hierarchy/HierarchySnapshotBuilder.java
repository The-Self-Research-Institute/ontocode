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
        Map<OWLClass, Set<OWLClass>> childrenIndex = assertedChildrenIndex(ont, importsScope);
        return reasoner
                .getSubClasses(df.getOWLThing(), true)
                .entities()
                .filter(c -> !c.isOWLNothing() && !c.isAnonymous())
                .filter(c -> importsScope == Imports.INCLUDED || isInActiveOntology(ont, c))
                .filter(c -> !hasNamedSuperclassViaReasoner(reasoner, df, c))
                .sorted(Comparator.comparing(c -> getLabel(ont, c, importsScope).toLowerCase(Locale.ROOT)))
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .map(c -> toTreeNode(ont, reasoner, c, null, importsScope, childrenIndex))
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
        Map<OWLClass, Set<OWLClass>> childrenIndex = assertedChildrenIndex(ont, importsScope);
        return assertedTopLevelCandidates(ont, importsScope).stream()
                .sorted(Comparator.comparing(c -> getLabel(ont, c, importsScope).toLowerCase(Locale.ROOT)))
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .map(c -> toTreeNodeAsserted(ont, c, null, importsScope, childrenIndex))
                .collect(Collectors.toList());
    }

    /**
     * Flat list of every named class with its asserted parents — OWLAPI equivalent of the
     * SPARQL allClasses query used by the graph view. Reads the live in-memory model, so on
     * desktop it reflects mutations immediately instead of waiting for the deferred Fuseki sync.
     */
    public List<OntologyDto.TreeNode> buildAllClasses(OWLOntology ont, int limit, Imports importsScope) {
        List<OntologyDto.TreeNode> result = new ArrayList<>();
        for (OWLClass cls : ont.getClassesInSignature(importsScope)) {
            if (cls.isBuiltIn() || cls.isOWLNothing() || cls.isOWLThing()) {
                continue;
            }
            OntologyDto.TreeNode node = new OntologyDto.TreeNode();
            node.setId(cls.getIRI().toString());
            node.setLabel(getLabel(ont, cls, importsScope));
            String description = getAnnotation(ont, cls, "http://www.w3.org/2000/01/rdf-schema#comment", importsScope);
            node.setDescription(description == null ? "" : description);
            node.setHasChildren(true);
            List<String> parents = structuralNamedParents(ont, cls, importsScope).stream()
                    .map(p -> p.getIRI().toString())
                    .collect(Collectors.toList());
            if (!parents.isEmpty()) {
                node.setSubClassOf(parents);
                node.setParent(parents.get(0));
            }
            List<OntologyDto.ClassExpressionDto> expressions = extractSetOperatorExpressions(ont, cls, importsScope);
            if (!expressions.isEmpty()) {
                node.setClassExpressions(expressions);
            }
            // extractSetOperatorExpressions only covers anonymous equivalentClass expressions
            // (intersection/union/...) — a plain `A owl:equivalentClass B` between two named
            // classes was never surfaced here, unlike toTreeNode() below, so two such classes
            // rendered as fully disconnected nodes in any view backed by buildAllClasses (e.g.
            // the graph view's bulk fetch), unlike the hierarchy views which do show it.
            List<Map<String, String>> equivalentClasses = getEquivalentClasses(ont, cls);
            if (!equivalentClasses.isEmpty()) {
                node.setEquivalentClasses(equivalentClasses);
            }
            List<Map<String, String>> disjointClasses = getDisjointClasses(ont, cls);
            if (!disjointClasses.isEmpty()) {
                node.setDisjointWith(disjointClasses);
            }
            List<Map<String, String>> restrictions = getRestrictions(ont, cls);
            if (!restrictions.isEmpty()) {
                node.setRestrictions(restrictions);
            }
            result.add(node);
            if (result.size() >= Math.max(1, limit)) {
                break;
            }
        }
        result.sort(Comparator.comparing(n -> (n.getLabel() == null ? n.getId() : n.getLabel()).toLowerCase(Locale.ROOT)));
        return result;
    }

    /**
     * OWLAPI mirror of the SPARQL set-operator extraction in OntologyQueryService.allClasses:
     * anonymous union/intersection/complement/oneOf expressions this class participates in
     * via owl:equivalentClass or rdfs:subClassOf. Restrictions are intentionally excluded
     * (they belong to the restrictions milestone, not set operators).
     */
    private List<OntologyDto.ClassExpressionDto> extractSetOperatorExpressions(OWLOntology ont, OWLClass cls,
                                                                               Imports importsScope) {
        List<OntologyDto.ClassExpressionDto> out = new ArrayList<>();
        ont.equivalentClassesAxioms(cls).forEach(ax ->
                ax.classExpressions()
                        .filter(expr -> expr.isAnonymous() && !expr.equals(cls))
                        .forEach(expr -> addSetOperatorExpression(ont, cls, expr, "equivalentClass", importsScope, out)));
        ont.subClassAxiomsForSubClass(cls).forEach(ax -> {
            OWLClassExpression sup = ax.getSuperClass();
            if (sup.isAnonymous()) {
                addSetOperatorExpression(ont, cls, sup, "subClassOf", importsScope, out);
            }
        });
        return out;
    }

    private void addSetOperatorExpression(OWLOntology ont, OWLClass owner, OWLClassExpression expr,
                                          String axiomType, Imports importsScope,
                                          List<OntologyDto.ClassExpressionDto> out) {
        String expressionType;
        List<Map<String, String>> operands = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        if (expr instanceof OWLObjectUnionOf union) {
            expressionType = "union";
            union.operands().filter(op -> !op.isAnonymous())
                    .forEach(op -> addOperand(ont, op.asOWLClass().getIRI(), getLabel(ont, op.asOWLClass(), importsScope), operands, labels));
        } else if (expr instanceof OWLObjectIntersectionOf intersection) {
            expressionType = "intersection";
            intersection.operands().filter(op -> !op.isAnonymous())
                    .forEach(op -> addOperand(ont, op.asOWLClass().getIRI(), getLabel(ont, op.asOWLClass(), importsScope), operands, labels));
        } else if (expr instanceof OWLObjectComplementOf complement) {
            expressionType = "complement";
            OWLClassExpression op = complement.getOperand();
            if (!op.isAnonymous()) {
                addOperand(ont, op.asOWLClass().getIRI(), getLabel(ont, op.asOWLClass(), importsScope), operands, labels);
            }
        } else if (expr instanceof OWLObjectOneOf oneOf) {
            expressionType = "oneOf";
            oneOf.individuals().filter(OWLIndividual::isNamed)
                    .forEach(ind -> {
                        IRI iri = ind.asOWLNamedIndividual().getIRI();
                        addOperand(ont, iri, iri.getShortForm(), operands, labels);
                    });
        } else {
            return; // restrictions and other expression kinds are out of scope here
        }

        if (operands.isEmpty()) return;

        OntologyDto.ClassExpressionDto dto = new OntologyDto.ClassExpressionDto();
        dto.setId(owner.getIRI() + "#expr-" + expressionType + "-" + out.size());
        dto.setExpressionType(expressionType);
        dto.setAxiomType(axiomType);
        dto.setOperands(operands);
        dto.setDefinition(switch (expressionType) {
            case "union" -> String.join(" or ", labels);
            case "intersection" -> String.join(" and ", labels);
            case "complement" -> "not " + labels.get(0);
            case "oneOf" -> "{" + String.join(", ", labels) + "}";
            default -> String.join(", ", labels);
        });
        out.add(dto);
    }

    private void addOperand(OWLOntology ont, IRI iri, String label,
                            List<Map<String, String>> operands, List<String> labels) {
        for (Map<String, String> existing : operands) {
            if (iri.toString().equals(existing.get("iri"))) return;
        }
        Map<String, String> operand = new LinkedHashMap<>();
        operand.put("iri", iri.toString());
        operand.put("label", label == null || label.isBlank() ? iri.getShortForm() : label);
        operands.add(operand);
        labels.add(operand.get("label"));
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
        Map<OWLClass, Set<OWLClass>> childrenIndex = assertedChildrenIndex(ont, importsScope);
        return childrenIndex.getOrDefault(parent, Set.of()).stream()
                .sorted(Comparator.comparing(c -> getLabel(ont, c, importsScope).toLowerCase(Locale.ROOT)))
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .map(c -> toTreeNode(ont, reasoner, c, parentIri, importsScope, childrenIndex))
                .collect(Collectors.toList());
    }

    /**
     * Precomputes direct children for every named class parent (Protégé asserted hierarchy).
     */
    public Map<String, List<OntologyDto.TreeNode>> buildChildrenIndex(OWLOntology ont, OWLReasoner reasoner) {
        Map<OWLClass, Set<OWLClass>> childrenIndex = assertedChildrenIndex(ont, Imports.EXCLUDED);

        Map<String, List<OntologyDto.TreeNode>> index = new HashMap<>();
        for (Map.Entry<OWLClass, Set<OWLClass>> e : childrenIndex.entrySet()) {
            String parentIri = e.getKey().getIRI().toString();
            List<OntologyDto.TreeNode> nodes = e.getValue().stream()
                    .sorted(Comparator.comparing(c -> getLabel(ont, c, Imports.EXCLUDED).toLowerCase(Locale.ROOT)))
                    .map(c -> toTreeNode(ont, reasoner, c, parentIri, Imports.EXCLUDED, childrenIndex))
                    .collect(Collectors.toList());
            index.put(parentIri, nodes);
        }
        return index;
    }

    /**
     * One-pass parent -&gt; children index over the WHOLE ontology (Protégé asserted semantics:
     * direct subClassOf plus equivalentClass/intersectionOf-derived membership, same as
     * {@link #structuralNamedParents} inverted). Building this once per request and reusing it
     * for every rendered node is O(totalClasses) total instead of O(totalClasses) PER rendered
     * node - on a 50k-class ontology that's the difference between milliseconds and minutes,
     * and unlike a subClassOf-only shortcut it still finds equivalentClass-only children.
     */
    private Map<OWLClass, Set<OWLClass>> assertedChildrenIndex(OWLOntology ont, Imports importsScope) {
        Map<OWLClass, Set<OWLClass>> index = new HashMap<>();
        for (OWLClass cls : ont.getClassesInSignature(importsScope)) {
            if (cls.isBuiltIn() || cls.isOWLNothing()) {
                continue;
            }
            for (OWLClass parent : structuralNamedParents(ont, cls, importsScope)) {
                index.computeIfAbsent(parent, k -> new LinkedHashSet<>()).add(cls);
            }
        }
        return index;
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
                                                    Imports importsScope, Map<OWLClass, Set<OWLClass>> childrenIndex) {
        String iri = cls.getIRI().toString();
        String label = getLabel(ont, cls, importsScope);
        String description = getAnnotation(ont, cls, "http://purl.obolibrary.org/obo/IAO_0000115", importsScope);
        if (description == null) {
            description = getAnnotation(ont, cls, "http://www.w3.org/2000/01/rdf-schema#comment", importsScope);
        }
        boolean hasChildren = childrenIndex.containsKey(cls);
        List<Map<String, String>> equivalentClasses = getEquivalentClasses(ont, cls);
        List<Map<String, String>> disjointClasses = getDisjointClasses(ont, cls);
        List<Map<String, String>> restrictions = getRestrictions(ont, cls);

        OntologyDto.TreeNode node = new OntologyDto.TreeNode();
        node.setId(iri);
        node.setLabel(label);
        node.setDescription(description);
        node.setParent(parentIri);
        node.setHasChildren(hasChildren);
        if (!equivalentClasses.isEmpty()) {
            node.setEquivalentClasses(equivalentClasses);
        }
        if (!disjointClasses.isEmpty()) {
            node.setDisjointWith(disjointClasses);
        }
        if (!restrictions.isEmpty()) {
            node.setRestrictions(restrictions);
        }
        if (importsScope == Imports.INCLUDED) {
            node.setSourceOntology(findSourceOntologyIri(ont, cls));
        }
        return node;
    }

    private OntologyDto.TreeNode toTreeNode(OWLOntology ont, OWLReasoner reasoner,
                                            OWLClass cls, String parentIri, Imports importsScope,
                                            Map<OWLClass, Set<OWLClass>> childrenIndex) {
        String iri = cls.getIRI().toString();
        String label = getLabel(ont, cls, importsScope);
        String description = getAnnotation(ont, cls, "http://purl.obolibrary.org/obo/IAO_0000115", importsScope);
        if (description == null) {
            description = getAnnotation(ont, cls, "http://www.w3.org/2000/01/rdf-schema#comment", importsScope);
        }
        boolean hasChildren = childrenIndex.containsKey(cls);
        List<Map<String, String>> equivalentClasses = getEquivalentClasses(ont, cls);
        List<Map<String, String>> disjointClasses = getDisjointClasses(ont, cls);
        List<Map<String, String>> restrictions = getRestrictions(ont, cls);

        OntologyDto.TreeNode node = new OntologyDto.TreeNode();
        node.setId(iri);
        node.setLabel(label);
        node.setDescription(description);
        node.setParent(parentIri);
        node.setHasChildren(hasChildren);
        if (!equivalentClasses.isEmpty()) {
            node.setEquivalentClasses(equivalentClasses);
        }
        if (!disjointClasses.isEmpty()) {
            node.setDisjointWith(disjointClasses);
        }
        if (!restrictions.isEmpty()) {
            node.setRestrictions(restrictions);
        }
        if (importsScope == Imports.INCLUDED) {
            node.setSourceOntology(findSourceOntologyIri(ont, cls));
        }
        return node;
    }

    private String getLabel(OWLOntology ont, OWLClass cls, Imports importsScope) {
        return getEntityLabel(ont, cls.getIRI(), importsScope);
    }

    /** IRI-based rdfs:label lookup usable for any entity kind (class, property, individual). */
    private String getEntityLabel(OWLOntology ont, IRI iri, Imports importsScope) {
        IRI rdfsLabel = IRI.create("http://www.w3.org/2000/01/rdf-schema#label");
        Optional<String> label = ont.annotationAssertionAxioms(iri, importsScope)
                .filter(a -> a.getProperty().getIRI().equals(rdfsLabel))
                .sorted(Comparator.comparing(a -> langPriority(a.getValue())))
                .findFirst()
                .flatMap(a -> a.getValue().asLiteral())
                .map(OWLLiteral::getLiteral);
        return label.orElseGet(iri::getShortForm);
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
                    m.put("label", getLabel(ont, eq, org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED));
                    return m;
                })
                .collect(Collectors.toList());
    }

    /**
     * Property restrictions (someValuesFrom/allValuesFrom/hasValue/cardinality) this class
     * participates in via subClassOf or equivalentClass. Mirrors the SPARQL side's
     * buildRestrictionSparqlBody (OntologyQueryService.java) so both paths produce the same
     * {propertyIri, propertyLabel, restrictionType, fillerIri, fillerLabel, cardinality?,
     * axiomType} shape the graph view consumes.
     */
    private List<Map<String, String>> getRestrictions(OWLOntology ont, OWLClass cls) {
        List<Map<String, String>> result = new ArrayList<>();
        ont.subClassAxiomsForSubClass(cls).forEach(ax ->
                addRestrictionIfPresent(ont, ax.getSuperClass(), "subClassOf", result));
        ont.equivalentClassesAxioms(cls).forEach(ax ->
                ax.classExpressions()
                        .filter(ce -> !ce.equals(cls))
                        .forEach(ce -> addRestrictionIfPresent(ont, ce, "equivalentClass", result)));
        return result;
    }

    private void addRestrictionIfPresent(OWLOntology ont, OWLClassExpression ce, String axiomType,
                                         List<Map<String, String>> out) {
        Imports imp = Imports.EXCLUDED;
        OWLPropertyExpression prop;
        OWLObject filler;
        String restrictionType;
        String cardinality = null;

        if (ce instanceof OWLObjectSomeValuesFrom r) {
            prop = r.getProperty(); filler = r.getFiller(); restrictionType = "some";
        } else if (ce instanceof OWLObjectAllValuesFrom r) {
            prop = r.getProperty(); filler = r.getFiller(); restrictionType = "only";
        } else if (ce instanceof OWLObjectHasValue r) {
            prop = r.getProperty(); filler = r.getFiller(); restrictionType = "value";
        } else if (ce instanceof OWLObjectMinCardinality r) {
            prop = r.getProperty(); filler = r.getFiller(); restrictionType = "min"; cardinality = String.valueOf(r.getCardinality());
        } else if (ce instanceof OWLObjectMaxCardinality r) {
            prop = r.getProperty(); filler = r.getFiller(); restrictionType = "max"; cardinality = String.valueOf(r.getCardinality());
        } else if (ce instanceof OWLObjectExactCardinality r) {
            prop = r.getProperty(); filler = r.getFiller(); restrictionType = "exactly"; cardinality = String.valueOf(r.getCardinality());
        } else if (ce instanceof OWLDataSomeValuesFrom r) {
            prop = r.getProperty(); filler = r.getFiller(); restrictionType = "some";
        } else if (ce instanceof OWLDataAllValuesFrom r) {
            prop = r.getProperty(); filler = r.getFiller(); restrictionType = "only";
        } else if (ce instanceof OWLDataHasValue r) {
            prop = r.getProperty(); filler = r.getFiller(); restrictionType = "value";
        } else if (ce instanceof OWLDataMinCardinality r) {
            prop = r.getProperty(); filler = r.getFiller(); restrictionType = "min"; cardinality = String.valueOf(r.getCardinality());
        } else if (ce instanceof OWLDataMaxCardinality r) {
            prop = r.getProperty(); filler = r.getFiller(); restrictionType = "max"; cardinality = String.valueOf(r.getCardinality());
        } else if (ce instanceof OWLDataExactCardinality r) {
            prop = r.getProperty(); filler = r.getFiller(); restrictionType = "exactly"; cardinality = String.valueOf(r.getCardinality());
        } else {
            return; // anonymous set-operator expressions are handled separately (extractSetOperatorExpressions)
        }

        if (prop == null || prop.isAnonymous()) return;
        IRI propertyIri = prop instanceof OWLObjectPropertyExpression ope
                ? ope.asOWLObjectProperty().getIRI()
                : ((OWLDataPropertyExpression) prop).asOWLDataProperty().getIRI();
        String propIri = propertyIri.toString();
        String propLabel = getEntityLabel(ont, propertyIri, imp);

        String fillerIri;
        String fillerLabel;
        if (filler instanceof OWLClass fc) {
            fillerIri = fc.getIRI().toString();
            fillerLabel = getEntityLabel(ont, fc.getIRI(), imp);
        } else if (filler instanceof OWLDatatype dt) {
            fillerIri = dt.getIRI().toString();
            fillerLabel = dt.getIRI().getShortForm();
        } else if (filler instanceof OWLIndividual ind && ind.isNamed()) {
            fillerIri = ind.asOWLNamedIndividual().getIRI().toString();
            fillerLabel = ind.asOWLNamedIndividual().getIRI().getShortForm();
        } else if (filler instanceof OWLLiteral lit) {
            fillerIri = "";
            fillerLabel = lit.getLiteral();
        } else {
            return; // anonymous filler (nested restriction/class expression) — out of scope here
        }

        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("propertyIri", propIri);
        entry.put("propertyLabel", propLabel);
        entry.put("restrictionType", restrictionType);
        entry.put("fillerIri", fillerIri);
        entry.put("fillerLabel", fillerLabel);
        if (cardinality != null) entry.put("cardinality", cardinality);
        entry.put("axiomType", axiomType);
        out.add(entry);
    }

    /**
     * Pairwise and n-ary (owl:AllDisjointClasses) disjointness partners for a class.
     * OWLAPI models both as OWLDisjointClassesAxiom, whose classExpressions() lists every
     * member of the disjoint group (2 for a pairwise disjointWith, N for AllDisjointClasses).
     */
    private List<Map<String, String>> getDisjointClasses(OWLOntology ont, OWLClass cls) {
        return ont.disjointClassesAxioms(cls)
                .flatMap(ax -> ax.classExpressions())
                .filter(ce -> !ce.equals(cls) && !ce.isAnonymous())
                .map(ce -> {
                    OWLClass dc = ce.asOWLClass();
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("iri", dc.getIRI().toString());
                    m.put("label", getLabel(ont, dc, org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED));
                    return m;
                })
                .distinct()
                .collect(Collectors.toList());
    }
}
