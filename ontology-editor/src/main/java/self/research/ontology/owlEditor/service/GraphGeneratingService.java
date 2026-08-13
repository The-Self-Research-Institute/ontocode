package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@EnableCaching
public class GraphGeneratingService {

    private static final Logger log = LoggerFactory.getLogger(GraphGeneratingService.class);

    public static class Node {
        private String id;
        private String label;
        private String type;
        private String iri;
        private int size = 10;
        private String color;
        private Map<String, Object> metadata = new HashMap<>();

        public Node() {}

        public Node(String id, String label, String type, String iri) {
            this.id = id;
            this.label = label;
            this.type = type;
            this.iri = iri;
            this.color = getColorForType(type);
        }

        private String getColorForType(String type) {
            switch (type) {
                case "class": return "#4A90E2";
                case "objectProperty": return "#50C878";
                case "dataProperty": return "#F39C12";
                case "individual": return "#E74C3C";
                case "annotationProperty": return "#9B59B6";
                default: return "#95A5A6";
            }
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getIri() { return iri; }
        public void setIri(String iri) { this.iri = iri; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }

    public static class Edge {
        private String id;
        private String from;
        private String to;
        private String type;
        private String label;
        private String color;
        private int width = 2;
        private Map<String, Object> metadata = new HashMap<>();

        public Edge() {}

        public Edge(String from, String to, String type, String label) {
            this.id = from + "_" + type + "_" + to;
            this.from = from;
            this.to = to;
            this.type = type;
            this.label = label;
            this.color = getColorForType(type);
        }

        private String getColorForType(String type) {
            switch (type) {
                case "subClassOf": return "#3498DB";
                case "instanceOf": return "#E74C3C";
                case "domain": return "#50C878";
                case "range": return "#F39C12";
                case "propertyRelation": return "#9B59B6";
                default: return "#95A5A6";
            }
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }

        public String getSource() { return from; }
        public void setSource(String source) { this.from = source; }
        public String getTarget() { return to; }
        public void setTarget(String target) { this.to = target; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public int getWidth() { return width; }
        public void setWidth(int width) { this.width = width; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }

    public static class Graph {
        private List<Node> nodes = new ArrayList<>();
        private List<Edge> edges = new ArrayList<>();
        private Map<String, Object> metadata = new HashMap<>();

        public List<Node> getNodes() { return nodes; }
        public void setNodes(List<Node> nodes) { this.nodes = nodes; }
        public List<Edge> getEdges() { return edges; }
        public void setEdges(List<Edge> edges) { this.edges = edges; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }

    @Cacheable(value = "graphCache", key = "#ontology.ontologyID.ontologyIRI.orElse(null) + '_' + #includeIndividuals")
    public Graph generateGraph(OWLOntology ontology, boolean includeIndividuals) {
        long startTime = System.currentTimeMillis();
        log.info("🔧 Generating optimized graph for ontology (this will be cached)");

        Graph graph = new Graph();
        Map<String, Node> nodeMap = new HashMap<>();

        for (OWLClass owlClass : ontology.getClassesInSignature()) {
            if (owlClass.isOWLThing() || owlClass.isOWLNothing()) continue;

            String id = getNodeId(owlClass.getIRI());
            String label = getLabel(owlClass, ontology);

            Node node = new Node(id, label, "class", owlClass.getIRI().toString());

            node.getMetadata().put("axiomCount", ontology.getAxioms(owlClass).size());
            node.getMetadata().put("subclassCount",
                ontology.getSubClassAxiomsForSuperClass(owlClass).size());

            nodeMap.put(id, node);
            graph.getNodes().add(node);
        }

        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
            if (property.isOWLTopObjectProperty() || property.isOWLBottomObjectProperty()) continue;

            String id = getNodeId(property.getIRI());
            String label = getLabel(property, ontology);

            Node node = new Node(id, label, "objectProperty", property.getIRI().toString());
            nodeMap.put(id, node);
            graph.getNodes().add(node);
        }

        if (includeIndividuals) {
            for (OWLNamedIndividual individual : ontology.getIndividualsInSignature()) {
                String id = getNodeId(individual.getIRI());
                String label = getLabel(individual, ontology);

                Node node = new Node(id, label, "individual", individual.getIRI().toString());
                node.setSize(8);
                nodeMap.put(id, node);
                graph.getNodes().add(node);
            }
        }

        for (OWLSubClassOfAxiom axiom : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            if (!axiom.getSubClass().isAnonymous() && !axiom.getSuperClass().isAnonymous()) {
                String subId = getNodeId(axiom.getSubClass().asOWLClass().getIRI());
                String superId = getNodeId(axiom.getSuperClass().asOWLClass().getIRI());

                if (nodeMap.containsKey(subId) && nodeMap.containsKey(superId)) {
                    Edge edge = new Edge(subId, superId, "subClassOf", "subClassOf");
                    graph.getEdges().add(edge);
                }
            }
        }

        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
            String propId = getNodeId(property.getIRI());

            for (OWLObjectPropertyDomainAxiom axiom : ontology.getObjectPropertyDomainAxioms(property)) {
                if (!axiom.getDomain().isAnonymous()) {
                    String domainId = getNodeId(axiom.getDomain().asOWLClass().getIRI());
                    if (nodeMap.containsKey(domainId) && nodeMap.containsKey(propId)) {
                        Edge edge = new Edge(propId, domainId, "domain", "domain");
                        graph.getEdges().add(edge);
                    }
                }
            }

            for (OWLObjectPropertyRangeAxiom axiom : ontology.getObjectPropertyRangeAxioms(property)) {
                if (!axiom.getRange().isAnonymous()) {
                    String rangeId = getNodeId(axiom.getRange().asOWLClass().getIRI());
                    if (nodeMap.containsKey(rangeId) && nodeMap.containsKey(propId)) {
                        Edge edge = new Edge(propId, rangeId, "range", "range");
                        graph.getEdges().add(edge);
                    }
                }
            }
        }

        if (includeIndividuals) {
            for (OWLClassAssertionAxiom axiom : ontology.getAxioms(AxiomType.CLASS_ASSERTION)) {
                if (!axiom.getIndividual().isAnonymous() && !axiom.getClassExpression().isAnonymous()) {
                    String indId = getNodeId(axiom.getIndividual().asOWLNamedIndividual().getIRI());
                    String classId = getNodeId(axiom.getClassExpression().asOWLClass().getIRI());

                    if (nodeMap.containsKey(indId) && nodeMap.containsKey(classId)) {
                        Edge edge = new Edge(indId, classId, "instanceOf", "instanceOf");
                        graph.getEdges().add(edge);
                    }
                }
            }
        }

        long generationTime = System.currentTimeMillis() - startTime;
        graph.getMetadata().put("nodeCount", graph.getNodes().size());
        graph.getMetadata().put("edgeCount", graph.getEdges().size());
        graph.getMetadata().put("includesIndividuals", includeIndividuals);
        graph.getMetadata().put("generationTimeMs", generationTime);
        graph.getMetadata().put("cached", false);

        log.info("✅ Generated optimized graph with {} nodes and {} edges in {}ms (cached for instant future access)",
            graph.getNodes().size(), graph.getEdges().size(), generationTime);

        return graph;
    }

    @CacheEvict(value = "graphCache", allEntries = true)
    public void clearGraphCache() {
        log.info("🧹 Graph cache cleared");
    }

    public Graph generateClassHierarchyGraph(OWLOntology ontology, OWLClass rootClass, int depth) {
        log.info("Generating class hierarchy graph for {}", rootClass.getIRI().getShortForm());

        Graph graph = new Graph();
        Map<String, Node> nodeMap = new HashMap<>();
        Set<OWLClass> visitedClasses = new HashSet<>();

        addClassWithHierarchy(ontology, rootClass, depth, graph, nodeMap, visitedClasses);

        graph.getMetadata().put("rootClass", rootClass.getIRI().toString());
        graph.getMetadata().put("depth", depth);

        return graph;
    }

    public Graph generatePropertyGraph(OWLOntology ontology) {
        log.info("Generating property graph");

        Graph graph = new Graph();
        Map<String, Node> nodeMap = new HashMap<>();

        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
            if (property.isOWLTopObjectProperty() || property.isOWLBottomObjectProperty()) continue;

            String id = getNodeId(property.getIRI());
            String label = getLabel(property, ontology);

            Node node = new Node(id, label, "objectProperty", property.getIRI().toString());
            nodeMap.put(id, node);
            graph.getNodes().add(node);
        }

        for (OWLSubObjectPropertyOfAxiom axiom : ontology.getAxioms(AxiomType.SUB_OBJECT_PROPERTY)) {
            if (!axiom.getSubProperty().isAnonymous() && !axiom.getSuperProperty().isAnonymous()) {
                String subId = getNodeId(axiom.getSubProperty().asOWLObjectProperty().getIRI());
                String superId = getNodeId(axiom.getSuperProperty().asOWLObjectProperty().getIRI());

                if (nodeMap.containsKey(subId) && nodeMap.containsKey(superId)) {
                    Edge edge = new Edge(subId, superId, "subPropertyOf", "subPropertyOf");
                    graph.getEdges().add(edge);
                }
            }
        }

        for (OWLInverseObjectPropertiesAxiom axiom : ontology.getAxioms(AxiomType.INVERSE_OBJECT_PROPERTIES)) {
            OWLObjectPropertyExpression first = axiom.getFirstProperty();
            OWLObjectPropertyExpression second = axiom.getSecondProperty();

            if (!first.isAnonymous() && !second.isAnonymous()) {
                String firstId = getNodeId(first.asOWLObjectProperty().getIRI());
                String secondId = getNodeId(second.asOWLObjectProperty().getIRI());

                if (nodeMap.containsKey(firstId) && nodeMap.containsKey(secondId)) {
                    Edge edge = new Edge(firstId, secondId, "inverseOf", "inverseOf");
                    edge.setColor("#9B59B6");
                    graph.getEdges().add(edge);
                }
            }
        }

        return graph;
    }

    private void addClassWithHierarchy(OWLOntology ontology, OWLClass owlClass, int depth,
                                      Graph graph, Map<String, Node> nodeMap, Set<OWLClass> visited) {
        if (depth <= 0 || visited.contains(owlClass)) {
            return;
        }

        visited.add(owlClass);

        String id = getNodeId(owlClass.getIRI());
        if (!nodeMap.containsKey(id)) {
            String label = getLabel(owlClass, ontology);
            Node node = new Node(id, label, "class", owlClass.getIRI().toString());
            nodeMap.put(id, node);
            graph.getNodes().add(node);
        }

        for (OWLSubClassOfAxiom axiom : ontology.getSubClassAxiomsForSuperClass(owlClass)) {
            if (!axiom.getSubClass().isAnonymous()) {
                OWLClass subClass = axiom.getSubClass().asOWLClass();
                addClassWithHierarchy(ontology, subClass, depth - 1, graph, nodeMap, visited);

                String subId = getNodeId(subClass.getIRI());
                Edge edge = new Edge(subId, id, "subClassOf", "subClassOf");
                graph.getEdges().add(edge);
            }
        }
    }

    private String getNodeId(IRI iri) {
        return iri.toString().replaceAll("[^a-zA-Z0-9]", "_");
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
}