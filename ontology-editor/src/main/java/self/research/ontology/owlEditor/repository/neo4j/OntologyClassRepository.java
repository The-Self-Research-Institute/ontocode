// src/main/java/.../repository/neo4j/OntologyClassRepository.java
package self.research.ontology.owlEditor.repository.neo4j;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.model.neo4j.OntologyClassNode;

import java.util.List;
import java.util.Optional;

@Repository
public interface OntologyClassRepository extends Neo4jRepository<OntologyClassNode, String> {

    List<OntologyClassNode> findByProjectId(String projectId);

    Optional<OntologyClassNode> findByIriAndProjectId(String iri, String projectId);

    @Query("MATCH (c:OntologyClass {project_id: $projectId}) " +
           "WHERE NOT (c)-[:SUBCLASS_OF]->() " +
           "RETURN c")
    List<OntologyClassNode> findRootClasses(String projectId);

    @Query("MATCH (child:OntologyClass)-[:SUBCLASS_OF]->(parent:OntologyClass {iri: $parentIri, project_id: $projectId}) " +
           "RETURN child")
    List<OntologyClassNode> findDirectSubClasses(String parentIri, String projectId);

    @Query("MATCH path = (child:OntologyClass {iri: $childIri})-[:SUBCLASS_OF*]->(ancestor:OntologyClass) " +
           "WHERE child.project_id = $projectId " +
           "RETURN ancestor " +
           "ORDER BY length(path)")
    List<OntologyClassNode> findAllAncestors(String childIri, String projectId);

    @Query("MATCH path = (parent:OntologyClass {iri: $parentIri})<-[:SUBCLASS_OF*]-(descendant:OntologyClass) " +
           "WHERE parent.project_id = $projectId " +
           "RETURN descendant " +
           "ORDER BY length(path)")
    List<OntologyClassNode> findAllDescendants(String parentIri, String projectId);

    @Query("MATCH (c:OntologyClass {project_id: $projectId}) " +
           "WHERE c.label =~ ('(?i).*' + $searchTerm + '.*') " +
           "RETURN c " +
           "LIMIT 50")
    List<OntologyClassNode> searchByLabel(String searchTerm, String projectId);

    @Query("MATCH (c:OntologyClass {project_id: $projectId}) " +
           "OPTIONAL MATCH (c)-[:SUBCLASS_OF]->(parent) " +
           "RETURN c, collect(parent) as parents")
    List<OntologyClassNode> findAllWithParents(String projectId);

    // Graph analytics
    @Query("MATCH (c:OntologyClass {project_id: $projectId}) " +
           "OPTIONAL MATCH (c)<-[:SUBCLASS_OF]-(child) " +
           "RETURN c.iri as classIri, c.label as label, count(child) as childCount " +
           "ORDER BY childCount DESC " +
           "LIMIT 20")
    List<ClassStatistics> findMostPopularClasses(String projectId);

    interface ClassStatistics {
        String getClassIri();
        String getLabel();
        Integer getChildCount();
    }
}