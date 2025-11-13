// src/main/java/.../service/Neo4jSyncService.java
package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import self.research.ontology.owlEditor.model.neo4j.OntologyClassNode;
import self.research.ontology.owlEditor.model.neo4j.PropertyNode;
import self.research.ontology.owlEditor.repository.neo4j.OntologyClassRepository;
import self.research.ontology.owlEditor.repository.neo4j.PropertyRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class Neo4jSyncService {

    private static final Logger log = LoggerFactory.getLogger(Neo4jSyncService.class);

    @Autowired
    private OntologyClassRepository classRepository;

    @Autowired
    private PropertyRepository propertyRepository;

    /**
     * Sync entire ontology to Neo4j after parsing
     */
    @Async("owlParsingExecutor")
    @Transactional
    public CompletableFuture<Void> syncOntologyToNeo4j(String projectId, OWLOntology ontology) {
        log.info("Starting Neo4j sync for project: {}", projectId);

        try {
            // Clear existing data for this project
            clearProjectData(projectId);

            // Map to store created nodes for relationship building
            Map<String, OntologyClassNode> classNodesMap = new HashMap<>();
            Map<String, PropertyNode> propertyNodesMap = new HashMap<>();

            // Step 1: Create class nodes
            for (OWLClass owlClass : ontology.getClassesInSignature()) {
                if (owlClass.isOWLThing() || owlClass.isOWLNothing()) continue;

                String iri = owlClass.getIRI().toString();
                String label = getLabel(owlClass, ontology);

                OntologyClassNode node = new OntologyClassNode(iri, label, projectId);
                node.setComment(getComment(owlClass, ontology));
                node.setDeprecated(isDeprecated(owlClass, ontology));

                classNodesMap.put(iri, node);
            }

            // Save all classes first
            classRepository.saveAll(classNodesMap.values());
            log.info("Created {} class nodes", classNodesMap.size());

            // Step 2: Build subclass relationships
            for (OWLClass owlClass : ontology.getClassesInSignature()) {
                if (owlClass.isOWLThing() || owlClass.isOWLNothing()) continue;

                String childIri = owlClass.getIRI().toString();
                OntologyClassNode childNode = classNodesMap.get(childIri);

                if (childNode != null) {
                    for (OWLSubClassOfAxiom axiom : ontology.getSubClassAxiomsForSubClass(owlClass)) {
                        if (!axiom.getSuperClass().isAnonymous()) {
                            OWLClass superClass = axiom.getSuperClass().asOWLClass();
                            String parentIri = superClass.getIRI().toString();
                            OntologyClassNode parentNode = classNodesMap.get(parentIri);

                            if (parentNode != null) {
                                childNode.getSuperClasses().add(parentNode);
                            }
                        }
                    }
                }
            }

            // Save relationships
            classRepository.saveAll(classNodesMap.values());
            log.info("Created subclass relationships");

            // Step 3: Create property nodes
            for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
                String iri = property.getIRI().toString();
                String label = getPropertyLabel(property, ontology);

                PropertyNode node = new PropertyNode(iri, label, "ObjectProperty", projectId);
                node.setFunctional(isFunctional(property, ontology));
                node.setInverseFunctional(isInverseFunctional(property, ontology));
                node.setTransitive(isTransitive(property, ontology));
                node.setSymmetric(isSymmetric(property, ontology));

                propertyNodesMap.put(iri, node);
            }

            for (OWLDataProperty property : ontology.getDataPropertiesInSignature()) {
                String iri = property.getIRI().toString();
                String label = getPropertyLabel(property, ontology);

                PropertyNode node = new PropertyNode(iri, label, "DatatypeProperty", projectId);
                node.setFunctional(isFunctional(property, ontology));

                propertyNodesMap.put(iri, node);
            }

            propertyRepository.saveAll(propertyNodesMap.values());
            log.info("Created {} property nodes", propertyNodesMap.size());

            log.info("Neo4j sync completed for project: {}", projectId);

        } catch (Exception e) {
            log.error("Failed to sync ontology to Neo4j for project: {}", projectId, e);
        }

        return CompletableFuture.completedFuture(null);
    }

    @Transactional
    public void clearProjectData(String projectId) {
        log.info("Clearing Neo4j data for project: {}", projectId);
        classRepository.deleteAll(classRepository.findByProjectId(projectId));
        propertyRepository.deleteAll(propertyRepository.findByProjectId(projectId));
    }

    // Helper methods to extract OWL annotations
    private String getLabel(OWLClass owlClass, OWLOntology ontology) {
        return ontology.getAnnotationAssertionAxioms(owlClass.getIRI()).stream()
                .filter(a -> a.getProperty().isLabel())
                .findFirst()
                .map(a -> a.getValue().asLiteral().get().getLiteral())
                .orElse(getLocalName(owlClass.getIRI().toString()));
    }

    private String getComment(OWLClass owlClass, OWLOntology ontology) {
        return ontology.getAnnotationAssertionAxioms(owlClass.getIRI()).stream()
                .filter(a -> a.getProperty().isComment())
                .findFirst()
                .map(a -> a.getValue().asLiteral().get().getLiteral())
                .orElse("");
    }

    private boolean isDeprecated(OWLClass owlClass, OWLOntology ontology) {
        return ontology.getAnnotationAssertionAxioms(owlClass.getIRI()).stream()
                .anyMatch(a -> a.getProperty().isDeprecated() && 
                              a.getValue().asLiteral().get().parseBoolean());
    }

    private String getPropertyLabel(OWLProperty property, OWLOntology ontology) {
        return ontology.getAnnotationAssertionAxioms(property.getIRI()).stream()
                .filter(a -> a.getProperty().isLabel())
                .findFirst()
                .map(a -> a.getValue().asLiteral().get().getLiteral())
                .orElse(getLocalName(property.getIRI().toString()));
    }

    private boolean isFunctional(OWLObjectProperty property, OWLOntology ontology) {
        return ontology.getFunctionalObjectPropertyAxioms(property).size() > 0;
    }

    private boolean isFunctional(OWLDataProperty property, OWLOntology ontology) {
        return ontology.getFunctionalDataPropertyAxioms(property).size() > 0;
    }

    private boolean isInverseFunctional(OWLObjectProperty property, OWLOntology ontology) {
        return ontology.getInverseFunctionalObjectPropertyAxioms(property).size() > 0;
    }

    private boolean isTransitive(OWLObjectProperty property, OWLOntology ontology) {
        return ontology.getTransitiveObjectPropertyAxioms(property).size() > 0;
    }

    private boolean isSymmetric(OWLObjectProperty property, OWLOntology ontology) {
        return ontology.getSymmetricObjectPropertyAxioms(property).size() > 0;
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