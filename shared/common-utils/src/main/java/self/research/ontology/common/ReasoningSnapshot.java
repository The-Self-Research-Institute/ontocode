package self.research.ontology.common;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;
import org.semanticweb.owlapi.reasoner.TimeOutException;
import org.semanticweb.owlapi.search.EntitySearcher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class ReasoningSnapshot {

    public static final int DEFAULT_TREE_DEPTH = 3;
    public static final int DEFAULT_MAX_MAPPED_CLASSES = 200_000;
    public static final int DEFAULT_INFERRED_AXIOM_LIMIT = 5000;

    private final OWLOntology ontology;
    private final OWLDataFactory df;
    private final OWLReasoner classReasoner;
    private final OWLReasoner propertyReasoner;
    private final Map<OWLEntity, String> labels = new HashMap<>();

    private ReasoningSnapshot(OWLOntology ontology, OWLReasoner classReasoner, OWLReasoner propertyReasoner) {
        this.ontology = ontology;
        this.df = ontology.getOWLOntologyManager().getOWLDataFactory();
        this.classReasoner = classReasoner;
        this.propertyReasoner = propertyReasoner != null ? propertyReasoner : classReasoner;
    }

    public static Map<String, Object> build(OWLOntology ontology, OWLReasoner classReasoner, OWLReasoner propertyReasoner) {
        return build(ontology, classReasoner, propertyReasoner,
                DEFAULT_TREE_DEPTH, DEFAULT_MAX_MAPPED_CLASSES, DEFAULT_INFERRED_AXIOM_LIMIT);
    }

    public static Map<String, Object> build(OWLOntology ontology, OWLReasoner classReasoner, OWLReasoner propertyReasoner,
                                            int treeDepth, int maxMappedClasses, int inferredAxiomLimit) {
        return new ReasoningSnapshot(ontology, classReasoner, propertyReasoner)
                .compute(treeDepth, maxMappedClasses, inferredAxiomLimit);
    }

    public static Map<String, Object> analyzeInconsistency(OWLOntology ontology) {
        return new ReasoningSnapshot(ontology, null, null).inconsistencyReport();
    }

    public static String label(OWLEntity entity, OWLOntology ontology) {
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        for (OWLAnnotation ann : EntitySearcher.getAnnotations(entity, ontology, factory.getRDFSLabel())
                .collect(Collectors.toList())) {
            if (ann.getValue() instanceof OWLLiteral literal) {
                return literal.getLiteral();
            }
        }
        return entity.getIRI().getShortForm();
    }

    public static void throwIfStopped() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ReasonerInterruptedException("Reasoning was stopped");
        }
    }

    public static void rethrowIfStopped(Exception e) {
        if (e instanceof ReasonerInterruptedException || e instanceof TimeOutException) {
            throw (RuntimeException) e;
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new ReasonerInterruptedException(e);
        }
    }

    private Map<String, Object> compute(int treeDepth, int maxMappedClasses, int inferredAxiomLimit) {
        long start = System.currentTimeMillis();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        int totalClasses = ontology.getClassesInSignature().size();
        snapshot.put("totalClasses", totalClasses);

        throwIfStopped();
        boolean consistent = classReasoner.isConsistent();
        snapshot.put("consistent", consistent);
        if (!consistent) {
            snapshot.put("inconsistency", inconsistencyReport());
            snapshot.put("unsatisfiableClasses", unsatisfiableWhenInconsistent());
            snapshot.put("durationMs", System.currentTimeMillis() - start);
            return snapshot;
        }

        precompute();
        snapshot.put("classificationMs", System.currentTimeMillis() - start);

        Set<OWLClass> unsatisfiable = null;
        try {
            unsatisfiable = new HashSet<>(classReasoner.getUnsatisfiableClasses().getEntities());
            unsatisfiable.remove(df.getOWLNothing());
        } catch (Exception e) {
            rethrowIfStopped(e);
        }
        boolean unsatisfiableKnown = unsatisfiable != null;
        snapshot.put("unsatisfiableKnown", unsatisfiableKnown);
        List<Map<String, Object>> unsatisfiableList = unsatisfiableKnown
                ? unsatisfiable.stream()
                        .map(cls -> Map.<String, Object>of("iri", cls.getIRI().toString(), "label", label(cls)))
                        .collect(Collectors.toList())
                : new ArrayList<>();
        snapshot.put("unsatisfiableClasses", unsatisfiableList);

        Set<String> visited = new HashSet<>();
        List<Map<String, Object>> classTree = new ArrayList<>();
        classTree.add(classNode(df.getOWLThing(), visited, treeDepth, unsatisfiable));
        if (!unsatisfiableList.isEmpty() || hasSubClassesOfNothing()) {
            classTree.add(classNode(df.getOWLNothing(), visited, treeDepth, unsatisfiable));
        }
        snapshot.put("classTree", classTree);
        snapshot.put("classTreeVisited", visited.size());

        putClassMap(snapshot, maxMappedClasses, unsatisfiable);

        List<Map<String, Object>> classHierarchyFlat = new ArrayList<>();
        classHierarchyFlat(df.getOWLThing(), classHierarchyFlat, new HashSet<>(), 0);
        snapshot.put("classHierarchyFlat", classHierarchyFlat);
        snapshot.put("equivalentClasses", equivalentClassGroups());

        snapshot.put("objectPropertyTree", List.of(objectPropertyTree()));
        snapshot.put("dataPropertyTree", List.of(dataPropertyTree()));
        snapshot.put("objectPropertyHierarchyFlat", objectPropertyFlat());
        snapshot.put("dataPropertyHierarchyFlat", dataPropertyFlat());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("classHierarchyNodes", totalClasses);
        stats.put("objectPropertyNodes", ontology.getObjectPropertiesInSignature().size());
        stats.put("dataPropertyNodes", ontology.getDataPropertiesInSignature().size());
        stats.put("individuals", ontology.getIndividualsInSignature().size());
        stats.put("satisfiableClasses", unsatisfiableKnown ? Math.max(0, totalClasses - unsatisfiableList.size()) : -1);
        stats.put("unsatisfiableClasses", unsatisfiableKnown ? unsatisfiableList.size() : -1);
        stats.put("isConsistent", true);
        snapshot.put("stats", stats);

        snapshot.put("inferredAxioms", inferredAxioms(inferredAxiomLimit));
        snapshot.put("durationMs", System.currentTimeMillis() - start);
        return snapshot;
    }

    private void precompute() {
        throwIfStopped();
        classReasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
        try {
            propertyReasoner.precomputeInferences(InferenceType.OBJECT_PROPERTY_HIERARCHY,
                    InferenceType.DATA_PROPERTY_HIERARCHY);
        } catch (Exception e) {
            rethrowIfStopped(e);
        }
    }

    private String label(OWLEntity entity) {
        return labels.computeIfAbsent(entity, e -> label(e, ontology));
    }

    private List<Map<String, Object>> unsatisfiableWhenInconsistent() {
        try {
            Set<OWLClass> unsat = new HashSet<>(classReasoner.getUnsatisfiableClasses().getEntities());
            unsat.remove(df.getOWLNothing());
            return unsat.stream()
                    .map(cls -> Map.<String, Object>of("iri", cls.getIRI().toString(), "label", label(cls)))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            rethrowIfStopped(e);
            return new ArrayList<>();
        }
    }

    private boolean hasSubClassesOfNothing() {
        try {
            return !classReasoner.getSubClasses(df.getOWLNothing(), true).isEmpty();
        } catch (Exception e) {
            rethrowIfStopped(e);
            return false;
        }
    }

    private boolean isUnsatisfiable(OWLClass owlClass, Set<OWLClass> unsatisfiable) {
        if (owlClass.isOWLNothing()) {
            return true;
        }
        if (unsatisfiable != null) {
            return unsatisfiable.contains(owlClass);
        }
        try {
            return !classReasoner.isSatisfiable(owlClass);
        } catch (Exception e) {
            rethrowIfStopped(e);
            return false;
        }
    }

    private List<Map<String, String>> equivalentsOf(OWLClass owlClass) {
        return classReasoner.getEquivalentClasses(owlClass).getEntities().stream()
                .filter(cls -> !cls.equals(owlClass))
                .map(cls -> Map.of("iri", cls.getIRI().toString(), "label", label(cls)))
                .collect(Collectors.toList());
    }

    private Map<String, Object> classNode(OWLClass owlClass, Set<String> visited, int maxDepth, Set<OWLClass> unsatisfiable) {
        throwIfStopped();
        String iri = owlClass.getIRI().toString();
        List<Map<String, String>> equivalentClasses = equivalentsOf(owlClass);

        if (visited.contains(iri) && !owlClass.isOWLThing() && !owlClass.isOWLNothing()) {
            return Map.of("id", iri, "label", label(owlClass),
                    "children", List.of(), "hasChildren", false, "equivalentClasses", equivalentClasses);
        }
        visited.add(iri);

        NodeSet<OWLClass> subClasses = classReasoner.getSubClasses(owlClass, true);
        boolean hasAnyChildren = subClasses.getFlattened().stream()
                .anyMatch(c -> !c.isOWLNothing() && !c.equals(owlClass));

        List<Map<String, Object>> children = new ArrayList<>();
        if (maxDepth > 0) {
            for (Node<OWLClass> subClassNode : subClasses) {
                OWLClass representative = subClassNode.getRepresentativeElement();
                if (representative.isOWLNothing() && !owlClass.isOWLThing()) continue;
                if (representative.equals(owlClass)) continue;
                children.add(classNode(representative, visited, maxDepth - 1, unsatisfiable));
            }
            children.sort(Comparator.comparing(m -> m.get("label").toString()));
        }

        Map<String, Object> node = new HashMap<>();
        node.put("id", iri);
        node.put("label", label(owlClass));
        node.put("children", children);
        node.put("hasChildren", hasAnyChildren);
        node.put("type", "Class");
        node.put("equivalentClasses", equivalentClasses);
        if (isUnsatisfiable(owlClass, unsatisfiable)) {
            node.put("isUnsatisfiable", true);
        }
        return node;
    }

    private void putClassMap(Map<String, Object> snapshot, int maxMappedClasses, Set<OWLClass> unsatisfiable) {
        Map<String, List<String>> classChildren = new LinkedHashMap<>();
        Map<String, Map<String, Object>> classNodes = new LinkedHashMap<>();
        Deque<OWLClass> pending = new ArrayDeque<>();
        OWLClass thing = df.getOWLThing();
        pending.add(thing);
        classNodes.put(thing.getIRI().toString(), mappedNode(thing, unsatisfiable));

        while (!pending.isEmpty()) {
            throwIfStopped();
            OWLClass parent = pending.poll();
            List<OWLClass> children = new ArrayList<>();
            for (Node<OWLClass> node : classReasoner.getSubClasses(parent, true)) {
                OWLClass representative = node.getRepresentativeElement();
                if (representative.isOWLNothing() || representative.equals(parent)) continue;
                children.add(representative);
            }
            if (children.isEmpty()) {
                continue;
            }
            children.sort(Comparator.comparing(this::label));
            List<String> childIris = new ArrayList<>(children.size());
            for (OWLClass child : children) {
                String childIri = child.getIRI().toString();
                childIris.add(childIri);
                if (!classNodes.containsKey(childIri)) {
                    if (classNodes.size() >= maxMappedClasses) {
                        snapshot.put("classChildrenOmitted", true);
                        return;
                    }
                    classNodes.put(childIri, mappedNode(child, unsatisfiable));
                    pending.add(child);
                }
            }
            classChildren.put(parent.getIRI().toString(), childIris);
        }
        snapshot.put("classChildren", classChildren);
        snapshot.put("classNodes", classNodes);
    }

    private Map<String, Object> mappedNode(OWLClass owlClass, Set<OWLClass> unsatisfiable) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("label", label(owlClass));
        List<Map<String, String>> equivalents = equivalentsOf(owlClass);
        if (!equivalents.isEmpty()) {
            node.put("equivalentClasses", equivalents);
        }
        if (isUnsatisfiable(owlClass, unsatisfiable)) {
            node.put("isUnsatisfiable", true);
        }
        return node;
    }

    private void classHierarchyFlat(OWLClass parentClass, List<Map<String, Object>> hierarchy,
                                    Set<OWLClass> processedClasses, int depth) {
        if (processedClasses.contains(parentClass) || depth > 10) {
            return;
        }
        processedClasses.add(parentClass);

        for (Node<OWLClass> node : classReasoner.getSubClasses(parentClass, true)) {
            for (OWLClass subClass : node.getEntities()) {
                if (subClass.isOWLNothing()) {
                    continue;
                }
                throwIfStopped();
                Map<String, Object> classNode = new HashMap<>();
                classNode.put("iri", subClass.getIRI().toString());
                classNode.put("label", label(subClass));
                classNode.put("depth", depth);

                int childrenCount = (int) classReasoner.getSubClasses(subClass, true)
                        .entities()
                        .filter(c -> !c.isOWLNothing())
                        .count();
                classNode.put("childrenCount", childrenCount);
                hierarchy.add(classNode);

                if (depth < 5 && childrenCount > 0) {
                    classHierarchyFlat(subClass, hierarchy, processedClasses, depth + 1);
                }
            }
        }
    }

    private List<Map<String, Object>> equivalentClassGroups() {
        List<Map<String, Object>> groups = new ArrayList<>();
        for (OWLClass cls : ontology.getClassesInSignature()) {
            throwIfStopped();
            try {
                Set<OWLClass> equivalents = classReasoner.getEquivalentClasses(cls).getEntities();
                if (equivalents.size() > 1) {
                    Map<String, Object> group = new HashMap<>();
                    group.put("classes", equivalents.stream()
                            .map(c -> Map.<String, Object>of("iri", c.getIRI().toString(), "label", label(c)))
                            .collect(Collectors.toList()));
                    groups.add(group);
                }
            } catch (Exception e) {
                rethrowIfStopped(e);
            }
        }
        return groups;
    }

    private Map<String, Object> objectPropertyTree() {
        OWLObjectProperty top = df.getOWLTopObjectProperty();
        Map<String, Object> root;
        try {
            root = objectPropertyNode(top, new HashSet<>());
        } catch (Exception e) {
            rethrowIfStopped(e);
            root = Map.of("id", top.getIRI().toString(), "label", "owl:topObjectProperty",
                    "children", List.of(), "hasChildren", false, "type", "ObjectProperty");
        }
        if (((List<?>) root.get("children")).isEmpty()) {
            List<Map<String, Object>> asserted = ontology.getObjectPropertiesInSignature().stream()
                    .filter(p -> !p.isOWLTopObjectProperty() && !p.isOWLBottomObjectProperty())
                    .map(p -> Map.<String, Object>of("id", p.getIRI().toString(), "label", label(p),
                            "children", List.of(), "hasChildren", false, "type", "ObjectProperty"))
                    .collect(Collectors.toList());
            root = new HashMap<>(root);
            root.put("children", asserted);
            root.put("hasChildren", !asserted.isEmpty());
        }
        return root;
    }

    private Map<String, Object> objectPropertyNode(OWLObjectProperty property, Set<String> visited) {
        throwIfStopped();
        String iri = property.getIRI().toString();
        List<Map<String, String>> equivalentProperties =
                propertyReasoner.getEquivalentObjectProperties(property).getEntities().stream()
                        .filter(p -> !p.equals(property) && !p.isAnonymous())
                        .map(p -> Map.of("iri", p.asOWLObjectProperty().getIRI().toString(),
                                "label", label(p.asOWLObjectProperty())))
                        .collect(Collectors.toList());

        if (visited.contains(iri) && !property.isOWLTopObjectProperty()) {
            return Map.of("id", iri, "label", label(property),
                    "children", List.of(), "hasChildren", false, "equivalentProperties", equivalentProperties);
        }
        visited.add(iri);

        List<Map<String, Object>> children = new ArrayList<>();
        for (Node<OWLObjectPropertyExpression> node : propertyReasoner.getSubObjectProperties(property, true)) {
            OWLObjectPropertyExpression rep = node.getRepresentativeElement();
            if (rep.isAnonymous()) continue;
            OWLObjectProperty sub = rep.asOWLObjectProperty();
            if (sub.isOWLBottomObjectProperty() || sub.equals(property)) continue;
            children.add(objectPropertyNode(sub, visited));
        }
        children.sort(Comparator.comparing(m -> m.get("label").toString()));

        Map<String, Object> result = new HashMap<>();
        result.put("id", iri);
        result.put("label", label(property));
        result.put("children", children);
        result.put("hasChildren", !children.isEmpty());
        result.put("type", "ObjectProperty");
        result.put("equivalentProperties", equivalentProperties);
        return result;
    }

    private Map<String, Object> dataPropertyTree() {
        OWLDataProperty top = df.getOWLTopDataProperty();
        Map<String, Object> root;
        try {
            root = dataPropertyNode(top, new HashSet<>());
        } catch (Exception e) {
            rethrowIfStopped(e);
            root = Map.of("id", top.getIRI().toString(), "label", "owl:topDataProperty",
                    "children", List.of(), "hasChildren", false, "type", "DataProperty");
        }
        if (((List<?>) root.get("children")).isEmpty()) {
            List<Map<String, Object>> asserted = ontology.getDataPropertiesInSignature().stream()
                    .filter(p -> !p.isOWLTopDataProperty() && !p.isOWLBottomDataProperty())
                    .map(p -> Map.<String, Object>of("id", p.getIRI().toString(), "label", label(p),
                            "children", List.of(), "hasChildren", false, "type", "DataProperty"))
                    .collect(Collectors.toList());
            root = new HashMap<>(root);
            root.put("children", asserted);
            root.put("hasChildren", !asserted.isEmpty());
        }
        return root;
    }

    private Map<String, Object> dataPropertyNode(OWLDataProperty property, Set<String> visited) {
        throwIfStopped();
        String iri = property.getIRI().toString();
        if (visited.contains(iri) && !property.isOWLTopDataProperty()) {
            return Map.of("id", iri, "label", label(property),
                    "children", List.of(), "hasChildren", false, "type", "DataProperty");
        }
        visited.add(iri);

        List<Map<String, Object>> children = new ArrayList<>();
        for (Node<OWLDataProperty> node : propertyReasoner.getSubDataProperties(property, true)) {
            OWLDataProperty sub = node.getRepresentativeElement();
            if (sub.isOWLBottomDataProperty() || sub.equals(property)) continue;
            children.add(dataPropertyNode(sub, visited));
        }
        children.sort(Comparator.comparing(m -> m.get("label").toString()));

        Map<String, Object> result = new HashMap<>();
        result.put("id", iri);
        result.put("label", label(property));
        result.put("children", children);
        result.put("hasChildren", !children.isEmpty());
        result.put("type", "DataProperty");
        return result;
    }

    private List<Map<String, Object>> objectPropertyFlat() {
        List<Map<String, Object>> hierarchy = new ArrayList<>();
        try {
            objectPropertyFlat(df.getOWLTopObjectProperty(), hierarchy, new HashSet<>(), 0);
        } catch (Exception e) {
            rethrowIfStopped(e);
            hierarchy.clear();
        }
        if (hierarchy.isEmpty()) {
            for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
                if (!prop.isOWLTopObjectProperty() && !prop.isOWLBottomObjectProperty()) {
                    hierarchy.add(flatPropertyLeaf(prop));
                }
            }
        }
        return hierarchy;
    }

    private void objectPropertyFlat(OWLObjectProperty parent, List<Map<String, Object>> hierarchy,
                                    Set<OWLObjectProperty> processed, int depth) {
        if (processed.contains(parent) || depth > 10) {
            return;
        }
        processed.add(parent);

        for (Node<OWLObjectPropertyExpression> node : propertyReasoner.getSubObjectProperties(parent, true)) {
            for (OWLObjectPropertyExpression expr : node.getEntities()) {
                if (expr.isAnonymous() || expr.asOWLObjectProperty().isOWLBottomObjectProperty()) {
                    continue;
                }
                throwIfStopped();
                OWLObjectProperty sub = expr.asOWLObjectProperty();
                Map<String, Object> propNode = new HashMap<>();
                propNode.put("iri", sub.getIRI().toString());
                propNode.put("label", label(sub));
                propNode.put("depth", depth);
                int childrenCount = (int) propertyReasoner.getSubObjectProperties(sub, true)
                        .entities()
                        .filter(p -> !p.isAnonymous() && !p.asOWLObjectProperty().isOWLBottomObjectProperty())
                        .count();
                propNode.put("childrenCount", childrenCount);
                hierarchy.add(propNode);
                if (depth < 5 && childrenCount > 0) {
                    objectPropertyFlat(sub, hierarchy, processed, depth + 1);
                }
            }
        }
    }

    private List<Map<String, Object>> dataPropertyFlat() {
        List<Map<String, Object>> hierarchy = new ArrayList<>();
        try {
            dataPropertyFlat(df.getOWLTopDataProperty(), hierarchy, new HashSet<>(), 0);
        } catch (Exception e) {
            rethrowIfStopped(e);
            hierarchy.clear();
        }
        if (hierarchy.isEmpty()) {
            for (OWLDataProperty prop : ontology.getDataPropertiesInSignature()) {
                if (!prop.isOWLTopDataProperty() && !prop.isOWLBottomDataProperty()) {
                    hierarchy.add(flatPropertyLeaf(prop));
                }
            }
        }
        return hierarchy;
    }

    private void dataPropertyFlat(OWLDataProperty parent, List<Map<String, Object>> hierarchy,
                                  Set<OWLDataProperty> processed, int depth) {
        if (processed.contains(parent) || depth > 10) {
            return;
        }
        processed.add(parent);

        for (Node<OWLDataProperty> node : propertyReasoner.getSubDataProperties(parent, true)) {
            for (OWLDataProperty sub : node.getEntities()) {
                if (sub.isOWLBottomDataProperty()) {
                    continue;
                }
                throwIfStopped();
                Map<String, Object> propNode = new HashMap<>();
                propNode.put("iri", sub.getIRI().toString());
                propNode.put("label", label(sub));
                propNode.put("depth", depth);
                int childrenCount = (int) propertyReasoner.getSubDataProperties(sub, true)
                        .entities()
                        .filter(p -> !p.isOWLBottomDataProperty())
                        .count();
                propNode.put("childrenCount", childrenCount);
                hierarchy.add(propNode);
                if (depth < 5 && childrenCount > 0) {
                    dataPropertyFlat(sub, hierarchy, processed, depth + 1);
                }
            }
        }
    }

    private Map<String, Object> flatPropertyLeaf(OWLEntity property) {
        Map<String, Object> node = new HashMap<>();
        node.put("iri", property.getIRI().toString());
        node.put("label", label(property));
        node.put("depth", 0);
        node.put("childrenCount", 0);
        return node;
    }

    private Map<String, Object> inferredAxioms(int limit) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            classReasoner.precomputeInferences(InferenceType.CLASS_ASSERTIONS);
            Set<OWLAxiom> inferred = new LinkedHashSet<>();
            boolean stoppedEarly = false;
            for (OWLClass owlClass : ontology.getClassesInSignature()) {
                throwIfStopped();
                if (inferred.size() > limit) {
                    stoppedEarly = true;
                    break;
                }
                if (owlClass.isOWLThing() || owlClass.isOWLNothing()) {
                    continue;
                }
                for (OWLClass superClass : classReasoner.getSuperClasses(owlClass, false).getFlattened()) {
                    if (!superClass.isOWLThing()) {
                        inferred.add(df.getOWLSubClassOfAxiom(owlClass, superClass));
                    }
                }
                for (OWLNamedIndividual individual : classReasoner.getInstances(owlClass, false).getFlattened()) {
                    inferred.add(df.getOWLClassAssertionAxiom(owlClass, individual));
                }
            }
            boolean truncated = inferred.size() > limit;
            List<Map<String, Object>> axioms = inferred.stream()
                    .limit(limit)
                    .map(axiom -> {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("axiomType", axiom.getAxiomType().getName());
                        entry.put("axiom", axiom.toString());
                        entry.put("readable", formatAxiom(axiom));
                        Map<String, Object> entailment = JustificationRendering.describeEntailment(axiom);
                        if (entailment != null) {
                            entry.put("entailment", entailment);
                        }
                        return entry;
                    })
                    .collect(Collectors.toList());

            result.put("totalInferredAxioms", inferred.size());
            result.put("axioms", axioms);
            if (stoppedEarly) {
                result.put("totalIsPartial", true);
                result.put("message", "Showing the first " + axioms.size() + " of at least " + inferred.size()
                        + " inferred axioms (the search stopped early, so there may be more)");
            } else if (truncated) {
                result.put("message", "Showing the first " + axioms.size() + " of " + inferred.size() + " inferred axioms");
            } else {
                result.put("message", "Showing all " + inferred.size() + " inferred axioms");
            }
        } catch (Exception e) {
            rethrowIfStopped(e);
            result.put("error", e.getMessage() != null ? e.getMessage() : String.valueOf(e));
        }
        result.put("durationMs", System.currentTimeMillis() - start);
        return result;
    }

    private String formatAxiom(OWLAxiom axiom) {
        String text = axiom.toString();
        for (OWLEntity entity : axiom.getSignature()) {
            text = text.replace(entity.getIRI().toString(), label(entity));
        }
        return text;
    }

    private Map<String, Object> inconsistencyReport() {
        List<Map<String, Object>> issues = new ArrayList<>();

        for (OWLNamedIndividual ind : ontology.getIndividualsInSignature()) {
            List<OWLClassExpression> assertedTypes = EntitySearcher.getTypes(ind, ontology)
                    .collect(Collectors.toList());
            List<OWLClass> namedTypes = assertedTypes.stream()
                    .filter(t -> !t.isAnonymous())
                    .map(OWLClassExpression::asOWLClass)
                    .collect(Collectors.toList());

            for (OWLClass namedType : namedTypes) {
                if (assertedTypes.contains(df.getOWLObjectComplementOf(namedType))) {
                    Map<String, Object> issue = new HashMap<>();
                    issue.put("type", "complement_conflict");
                    issue.put("individual", label(ind));
                    issue.put("iri", ind.getIRI().toString());
                    issue.put("message", "\"" + label(ind) + "\" is assigned to both \""
                            + label(namedType) + "\" and its complement — these cannot both be true.");
                    issues.add(issue);
                    break;
                }
            }

            for (int i = 0; i < namedTypes.size(); i++) {
                OWLClass typeA = namedTypes.get(i);
                Set<OWLClass> disjointWithA = ontology.getDisjointClassesAxioms(typeA).stream()
                        .flatMap(ax -> ax.getClassExpressions().stream())
                        .filter(e -> !e.isAnonymous() && !e.equals(typeA))
                        .map(OWLClassExpression::asOWLClass)
                        .collect(Collectors.toSet());
                for (int j = i + 1; j < namedTypes.size(); j++) {
                    OWLClass typeB = namedTypes.get(j);
                    if (disjointWithA.contains(typeB)) {
                        Map<String, Object> issue = new HashMap<>();
                        issue.put("type", "disjoint_conflict");
                        issue.put("individual", label(ind));
                        issue.put("iri", ind.getIRI().toString());
                        issue.put("conflictingTypes", List.of(label(typeA), label(typeB)));
                        issue.put("message", "\"" + label(ind) + "\" belongs to both \""
                                + label(typeA) + "\" and \"" + label(typeB)
                                + "\" — but these classes are declared disjoint.");
                        issues.add(issue);
                    }
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("inconsistent", true);
        result.put("issues", issues);
        String summary = issues.isEmpty()
                ? "The ontology is logically inconsistent. The conflict may involve property restrictions or complex class expressions. Check your disjoint constraints and complement definitions."
                : "Found " + issues.size() + " inconsistenc" + (issues.size() == 1 ? "y" : "ies") + " — see details below and fix them in the editor.";
        result.put("message", summary);
        return result;
    }
}
