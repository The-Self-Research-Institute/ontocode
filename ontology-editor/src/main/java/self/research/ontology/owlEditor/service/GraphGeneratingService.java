package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for generating graph representations of ontologies.
 * Converts OWL ontology structure into nodes and edges for visualization.
 */
@Service
public class GraphGeneratingService {

    private static final Logger log = LoggerFactory.getLogger(GraphGeneratingService.class);

    /**
     * Node in the ontology graph
     */
    public static class Node {
        private String id;
        private String label;
        private String type; // CLASS, OBJECT_PROPERTY, DATA_PROPERTY, INDIVIDUAL
        private String iri;
        private int size = 10;
        private String color;
        private Map<String, Object> metadata = new HashMap<>();

        // Constructors
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
                case "CLASS": return "#4A90E2";
                case "OBJECT_PROPERTY": return "#50C878";
                case "DATA_PROPERTY": return "#F39C12";
                case "INDIVIDUAL": return "#E74C3C";
                default: return "#95A5A6";
            }
        }

        // Getters and setters
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

    /**
     * Edge in the ontology graph
     */
    public static class Edge {
        private String id;
        private String source;
        private String target;
        private String type; // SUBCLASS_OF, INSTANCE_OF, DOMAIN, RANGE, etc.
        private String label;
        private String color;
        private int width = 2;
        private Map<String, Object> metadata = new HashMap<>();

        // Constructors
        public Edge() {}

        public Edge(String source, String target, String type, String label) {
            this.id = source + "_" + type + "_" + target;
            this.source = source;
            this.target = target;
            this.type = type;
            this.label = label;
            this.color = getColorForType(type);
        }

        private String getColorForType(String type) {
            switch (type) {
                case "SUBCLASS_OF": return "#3498DB";
                case "INSTANCE_OF": return "#E74C3C";
                case "DOMAIN": return "#50C878";
                case "RANGE": return "#F39C12";
                case "PROPERTY": return "#9B59B6";
                default: return "#95A5A6";
            }
        }

        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
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

    /**
     * Complete graph structure
     */
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

    /**
     * Generate complete graph from ontology
     */
    public Graph generateGraph(OWLOntology ontology, boolean includeIndividuals) {
        log.info("Generating graph for ontology");
        
        Graph graph = new Graph();
        Map<String, Node> nodeMap = new HashMap<>();

        // Add classes as nodes
        for (OWLClass owlClass : ontology.getClassesInSignature()) {
            if (owlClass.isOWLThing() || owlClass.isOWLNothing()) continue;
            
            String id = getNodeId(owlClass.getIRI());
            String label = getLabel(owlClass, ontology);
            
            Node node = new Node(id, label, "CLASS", owlClass.getIRI().toString());
            
            // Add metadata
            node.getMetadata().put("axiomCount", ontology.getAxioms(owlClass).size());
            node.getMetadata().put("subclassCount", 
                ontology.getSubClassAxiomsForSuperClass(owlClass).size());
            
            nodeMap.put(id, node);
            graph.getNodes().add(node);
        }

        // Add object properties as nodes
        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
            if (property.isOWLTopObjectProperty() || property.isOWLBottomObjectProperty()) continue;
            
            String id = getNodeId(property.getIRI());
            String label = getLabel(property, ontology);
            
            Node node = new Node(id, label, "OBJECT_PROPERTY", property.getIRI().toString());
            nodeMap.put(id, node);
            graph.getNodes().add(node);
        }

        // Add individuals if requested
        if (includeIndividuals) {
            for (OWLNamedIndividual individual : ontology.getIndividualsInSignature()) {
                String id = getNodeId(individual.getIRI());
                String label = getLabel(individual, ontology);
                
                Node node = new Node(id, label, "INDIVIDUAL", individual.getIRI().toString());
                node.setSize(8); // Smaller size for individuals
                nodeMap.put(id, node);
                graph.getNodes().add(node);
            }
        }

        // Add subclass relationships
        for (OWLSubClassOfAxiom axiom : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            if (!axiom.getSubClass().isAnonymous() && !axiom.getSuperClass().isAnonymous()) {
                String subId = getNodeId(axiom.getSubClass().asOWLClass().getIRI());
                String superId = getNodeId(axiom.getSuperClass().asOWLClass().getIRI());
                
                if (nodeMap.containsKey(subId) && nodeMap.containsKey(superId)) {
                    Edge edge = new Edge(subId, superId, "SUBCLASS_OF", "subClassOf");
                    graph.getEdges().add(edge);
                }
            }
        }

        // Add property domain/range relationships
        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
            String propId = getNodeId(property.getIRI());
            
            // Domain
            for (OWLObjectPropertyDomainAxiom axiom : ontology.getObjectPropertyDomainAxioms(property)) {
                if (!axiom.getDomain().isAnonymous()) {
                    String domainId = getNodeId(axiom.getDomain().asOWLClass().getIRI());
                    if (nodeMap.containsKey(domainId) && nodeMap.containsKey(propId)) {
                        Edge edge = new Edge(propId, domainId, "DOMAIN", "domain");
                        graph.getEdges().add(edge);
                    }
                }
            }
            
            // Range
            for (OWLObjectPropertyRangeAxiom axiom : ontology.getObjectPropertyRangeAxioms(property)) {
                if (!axiom.getRange().isAnonymous()) {
                    String rangeId = getNodeId(axiom.getRange().asOWLClass().getIRI());
                    if (nodeMap.containsKey(rangeId) && nodeMap.containsKey(propId)) {
                        Edge edge = new Edge(propId, rangeId, "RANGE", "range");
                        graph.getEdges().add(edge);
                    }
                }
            }
        }

        // Add instance relationships if individuals included
        if (includeIndividuals) {
            for (OWLClassAssertionAxiom axiom : ontology.getAxioms(AxiomType.CLASS_ASSERTION)) {
                if (!axiom.getIndividual().isAnonymous() && !axiom.getClassExpression().isAnonymous()) {
                    String indId = getNodeId(axiom.getIndividual().asOWLNamedIndividual().getIRI());
                    String classId = getNodeId(axiom.getClassExpression().asOWLClass().getIRI());
                    
                    if (nodeMap.containsKey(indId) && nodeMap.containsKey(classId)) {
                        Edge edge = new Edge(indId, classId, "INSTANCE_OF", "instanceOf");
                        graph.getEdges().add(edge);
                    }
                }
            }
        }

        // Add metadata
        graph.getMetadata().put("nodeCount", graph.getNodes().size());
        graph.getMetadata().put("edgeCount", graph.getEdges().size());
        graph.getMetadata().put("includesIndividuals", includeIndividuals);
        
        log.info("Generated graph with {} nodes and {} edges", 
            graph.getNodes().size(), graph.getEdges().size());
        
        return graph;
    }

    /**
     * Generate subgraph for a specific class and its hierarchy
     */
    public Graph generateClassHierarchyGraph(OWLOntology ontology, OWLClass rootClass, int depth) {
        log.info("Generating class hierarchy graph for {}", rootClass.getIRI().getShortForm());
        
        Graph graph = new Graph();
        Map<String, Node> nodeMap = new HashMap<>();
        Set<OWLClass> visitedClasses = new HashSet<>();
        
        // Add root class
        addClassWithHierarchy(ontology, rootClass, depth, graph, nodeMap, visitedClasses);
        
        graph.getMetadata().put("rootClass", rootClass.getIRI().toString());
        graph.getMetadata().put("depth", depth);
        
        return graph;
    }

    /**
     * Generate property graph
     */
    public Graph generatePropertyGraph(OWLOntology ontology) {
        log.info("Generating property graph");
        
        Graph graph = new Graph();
        Map<String, Node> nodeMap = new HashMap<>();

        // Add all properties
        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
            if (property.isOWLTopObjectProperty() || property.isOWLBottomObjectProperty()) continue;
            
            String id = getNodeId(property.getIRI());
            String label = getLabel(property, ontology);
            
            Node node = new Node(id, label, "OBJECT_PROPERTY", property.getIRI().toString());
            nodeMap.put(id, node);
            graph.getNodes().add(node);
        }

        // Add subproperty relationships
        for (OWLSubObjectPropertyOfAxiom axiom : ontology.getAxioms(AxiomType.SUB_OBJECT_PROPERTY)) {
            if (!axiom.getSubProperty().isAnonymous() && !axiom.getSuperProperty().isAnonymous()) {
                String subId = getNodeId(axiom.getSubProperty().asOWLObjectProperty().getIRI());
                String superId = getNodeId(axiom.getSuperProperty().asOWLObjectProperty().getIRI());
                
                if (nodeMap.containsKey(subId) && nodeMap.containsKey(superId)) {
                    Edge edge = new Edge(subId, superId, "SUBPROPERTY_OF", "subPropertyOf");
                    graph.getEdges().add(edge);
                }
            }
        }

        // Add inverse relationships
        for (OWLInverseObjectPropertiesAxiom axiom : ontology.getAxioms(AxiomType.INVERSE_OBJECT_PROPERTIES)) {
            OWLObjectPropertyExpression first = axiom.getFirstProperty();
            OWLObjectPropertyExpression second = axiom.getSecondProperty();
            
            if (!first.isAnonymous() && !second.isAnonymous()) {
                String firstId = getNodeId(first.asOWLObjectProperty().getIRI());
                String secondId = getNodeId(second.asOWLObjectProperty().getIRI());
                
                if (nodeMap.containsKey(firstId) && nodeMap.containsKey(secondId)) {
                    Edge edge = new Edge(firstId, secondId, "INVERSE_OF", "inverseOf");
                    edge.setColor("#9B59B6");
                    graph.getEdges().add(edge);
                }
            }
        }

        return graph;
    }

    // Helper methods

    private void addClassWithHierarchy(OWLOntology ontology, OWLClass owlClass, int depth,
                                      Graph graph, Map<String, Node> nodeMap, Set<OWLClass> visited) {
        if (depth <= 0 || visited.contains(owlClass)) {
            return;
        }
        
        visited.add(owlClass);
        
        String id = getNodeId(owlClass.getIRI());
        if (!nodeMap.containsKey(id)) {
            String label = getLabel(owlClass, ontology);
            Node node = new Node(id, label, "CLASS", owlClass.getIRI().toString());
            nodeMap.put(id, node);
            graph.getNodes().add(node);
        }
        
        // Add subclasses
        for (OWLSubClassOfAxiom axiom : ontology.getSubClassAxiomsForSuperClass(owlClass)) {
            if (!axiom.getSubClass().isAnonymous()) {
                OWLClass subClass = axiom.getSubClass().asOWLClass();
                addClassWithHierarchy(ontology, subClass, depth - 1, graph, nodeMap, visited);
                
                String subId = getNodeId(subClass.getIRI());
                Edge edge = new Edge(subId, id, "SUBCLASS_OF", "subClassOf");
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