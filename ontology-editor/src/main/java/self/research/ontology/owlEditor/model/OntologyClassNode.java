// src/main/java/.../model/neo4j/OntologyClassNode.java
package self.research.ontology.owlEditor.model.neo4j;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

@Node("OntologyClass")
public class OntologyClassNode {

    @Id
    private String iri;

    @Property("label")
    private String label;

    @Property("comment")
    private String comment;

    @Property("deprecated")
    private boolean deprecated;

    @Property("project_id")
    private String projectId;

    @Relationship(type = "SUBCLASS_OF", direction = Relationship.Direction.OUTGOING)
    private Set<OntologyClassNode> superClasses = new HashSet<>();

    @Relationship(type = "SUBCLASS_OF", direction = Relationship.Direction.INCOMING)
    private Set<OntologyClassNode> subClasses = new HashSet<>();

    @Relationship(type = "EQUIVALENT_TO")
    private Set<OntologyClassNode> equivalentClasses = new HashSet<>();

    @Relationship(type = "DISJOINT_WITH")
    private Set<OntologyClassNode> disjointClasses = new HashSet<>();

    // Constructors, getters, setters
    public OntologyClassNode() {}

    public OntologyClassNode(String iri, String label, String projectId) {
        this.iri = iri;
        this.label = label;
        this.projectId = projectId;
    }

    // Getters and Setters
    public String getIri() { return iri; }
    public void setIri(String iri) { this.iri = iri; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public boolean isDeprecated() { return deprecated; }
    public void setDeprecated(boolean deprecated) { this.deprecated = deprecated; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public Set<OntologyClassNode> getSuperClasses() { return superClasses; }
    public void setSuperClasses(Set<OntologyClassNode> superClasses) { this.superClasses = superClasses; }

    public Set<OntologyClassNode> getSubClasses() { return subClasses; }
    public void setSubClasses(Set<OntologyClassNode> subClasses) { this.subClasses = subClasses; }

    public Set<OntologyClassNode> getEquivalentClasses() { return equivalentClasses; }
    public void setEquivalentClasses(Set<OntologyClassNode> equivalentClasses) { 
        this.equivalentClasses = equivalentClasses; 
    }

    public Set<OntologyClassNode> getDisjointClasses() { return disjointClasses; }
    public void setDisjointClasses(Set<OntologyClassNode> disjointClasses) { 
        this.disjointClasses = disjointClasses; 
    }
}