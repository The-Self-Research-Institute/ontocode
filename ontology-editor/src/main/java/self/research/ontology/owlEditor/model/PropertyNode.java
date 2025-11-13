// src/main/java/.../model/neo4j/PropertyNode.java
package self.research.ontology.owlEditor.model.neo4j;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

@Node("OntologyProperty")
public class PropertyNode {

    @Id
    private String iri;

    @Property("label")
    private String label;

    @Property("type")
    private String type; // "ObjectProperty" or "DatatypeProperty"

    @Property("functional")
    private boolean functional;

    @Property("inverse_functional")
    private boolean inverseFunctional;

    @Property("transitive")
    private boolean transitive;

    @Property("symmetric")
    private boolean symmetric;

    @Property("project_id")
    private String projectId;

    @Relationship(type = "HAS_DOMAIN")
    private Set<OntologyClassNode> domains = new HashSet<>();

    @Relationship(type = "HAS_RANGE")
    private Set<OntologyClassNode> ranges = new HashSet<>();

    @Relationship(type = "SUBPROPERTY_OF")
    private Set<PropertyNode> superProperties = new HashSet<>();

    // Constructors, getters, setters
    public PropertyNode() {}

    public PropertyNode(String iri, String label, String type, String projectId) {
        this.iri = iri;
        this.label = label;
        this.type = type;
        this.projectId = projectId;
    }

    // Getters and Setters
    public String getIri() { return iri; }
    public void setIri(String iri) { this.iri = iri; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isFunctional() { return functional; }
    public void setFunctional(boolean functional) { this.functional = functional; }

    public boolean isInverseFunctional() { return inverseFunctional; }
    public void setInverseFunctional(boolean inverseFunctional) { 
        this.inverseFunctional = inverseFunctional; 
    }

    public boolean isTransitive() { return transitive; }
    public void setTransitive(boolean transitive) { this.transitive = transitive; }

    public boolean isSymmetric() { return symmetric; }
    public void setSymmetric(boolean symmetric) { this.symmetric = symmetric; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public Set<OntologyClassNode> getDomains() { return domains; }
    public void setDomains(Set<OntologyClassNode> domains) { this.domains = domains; }

    public Set<OntologyClassNode> getRanges() { return ranges; }
    public void setRanges(Set<OntologyClassNode> ranges) { this.ranges = ranges; }

    public Set<PropertyNode> getSuperProperties() { return superProperties; }
    public void setSuperProperties(Set<PropertyNode> superProperties) { 
        this.superProperties = superProperties; 
    }
}