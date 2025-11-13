// src/main/java/.../repository/neo4j/PropertyRepository.java
package self.research.ontology.owlEditor.repository.neo4j;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.neo4j.PropertyNode;

import java.util.List;
import java.util.Optional;

@Repository
public interface PropertyRepository extends Neo4jRepository<PropertyNode, String> {

    List<PropertyNode> findByProjectId(String projectId);

    Optional<PropertyNode> findByIriAndProjectId(String iri, String projectId);

    List<PropertyNode> findByTypeAndProjectId(String type, String projectId);

    @Query("MATCH (p:OntologyProperty {project_id: $projectId})-[:HAS_DOMAIN]->(domain:OntologyClass {iri: $classIri}) " +
           "RETURN p")
    List<PropertyNode> findByDomainClass(String classIri, String projectId);

    @Query("MATCH (p:OntologyProperty {project_id: $projectId})-[:HAS_RANGE]->(range:OntologyClass {iri: $classIri}) " +
           "RETURN p")
    List<PropertyNode> findByRangeClass(String classIri, String projectId);
}