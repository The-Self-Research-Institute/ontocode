package self.research.ontology.owlEditor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

// Based on your types.ts file
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OntologyDto {

    // For EntityHierarchy.tsx
    public static class TreeNode {
        private String id;
        private String label;
        private String description;
        private String parent;
        private List<TreeNode> children;
        private Boolean hasChildren;
        private Map<String, String> annotations;
        /** Each entry: {iri, label} — populated for asserted hierarchy nodes that have owl:equivalentClass */
        private List<Map<String, String>> equivalentClasses;

        // Getters & Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getParent() { return parent; }
        public void setParent(String parent) { this.parent = parent; }
        public List<TreeNode> getChildren() { return children; }
        public void setChildren(List<TreeNode> children) { this.children = children; }
        public Boolean getHasChildren() { return hasChildren; }
        public void setHasChildren(Boolean hasChildren) { this.hasChildren = hasChildren; }
        public Map<String, String> getAnnotations() { return annotations; }
        public void setAnnotations(Map<String, String> annotations) { this.annotations = annotations; }
        public List<Map<String, String>> getEquivalentClasses() { return equivalentClasses; }
        public void setEquivalentClasses(List<Map<String, String>> equivalentClasses) { this.equivalentClasses = equivalentClasses; }
    }

    // For PropertyEditor.tsx
    public static class PropertyDto {
        private String id;
        private String label;
        private String type; // ObjectProperty, DatatypeProperty, AnnotationProperty
        private List<String> domains;
        private List<String> ranges;
        private List<String> superProperties;
        private List<String> characteristics;
        private Map<String, String> annotations;
        
        // Getters & Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public List<String> getDomains() { return domains; }
        public void setDomains(List<String> domains) { this.domains = domains; }
        public List<String> getRanges() { return ranges; }
        public void setRanges(List<String> ranges) { this.ranges = ranges; }
        public List<String> getSuperProperties() { return superProperties; }
        public void setSuperProperties(List<String> superProperties) { this.superProperties = superProperties; }
        public List<String> getCharacteristics() { return characteristics; }
        public void setCharacteristics(List<String> characteristics) { this.characteristics = characteristics; }
        public Map<String, String> getAnnotations() { return annotations; }
        public void setAnnotations(Map<String, String> annotations) { this.annotations = annotations; }
    }

    // For IndividualEditor.tsx
    public static class IndividualDto {
        private String id;
        private String label;
        private List<String> types;
        private Map<String, String> annotations;
        
        // Getters & Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public List<String> getTypes() { return types; }
        public void setTypes(List<String> types) { this.types = types; }
        public Map<String, String> getAnnotations() { return annotations; }
        public void setAnnotations(Map<String, String> annotations) { this.annotations = annotations; }
    }
    
       public static class OntologyMetadataDto {
        private String ontologyIRI;
        private String versionIRI;
        private int classCount;
        private int objectPropertyCount;
        private int dataPropertyCount;
        private int individualCount;
        private int annotationPropertyCount;
        private int axiomCount;
        private int logicalAxiomCount;

        // Getters and Setters
        public String getOntologyIRI() { return ontologyIRI; }
        public void setOntologyIRI(String ontologyIRI) { this.ontologyIRI = ontologyIRI; }
        
        public String getVersionIRI() { return versionIRI; }
        public void setVersionIRI(String versionIRI) { this.versionIRI = versionIRI; }
        
        public int getClassCount() { return classCount; }
        public void setClassCount(int classCount) { this.classCount = classCount; }
        
        public int getObjectPropertyCount() { return objectPropertyCount; }
        public void setObjectPropertyCount(int objectPropertyCount) { this.objectPropertyCount = objectPropertyCount; }
        
        public int getDataPropertyCount() { return dataPropertyCount; }
        public void setDataPropertyCount(int dataPropertyCount) { this.dataPropertyCount = dataPropertyCount; }
        
        public int getIndividualCount() { return individualCount; }
        public void setIndividualCount(int individualCount) { this.individualCount = individualCount; }
        
        public int getAnnotationPropertyCount() { return annotationPropertyCount; }
        public void setAnnotationPropertyCount(int annotationPropertyCount) { this.annotationPropertyCount = annotationPropertyCount; }
        
        public int getAxiomCount() { return axiomCount; }
        public void setAxiomCount(int axiomCount) { this.axiomCount = axiomCount; }
        
        public int getLogicalAxiomCount() { return logicalAxiomCount; }
        public void setLogicalAxiomCount(int logicalAxiomCount) { this.logicalAxiomCount = logicalAxiomCount; }
    }


    
    // For Dashboard.tsx
    public static class OntologyPrefixDto {
        private String prefix;
        private String namespace;
        // ... Getters & Setters
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
    }

    // For StatisticsPanel.tsx
    public static class OntologyStatisticsDto {
        // ... (add all fields from OntologyStatistics in types.ts)
        private int classCount;
        private int objectPropertyCount;
        // ... Getters & Setters
        public int getClassCount() { return classCount; }
        public void setClassCount(int classCount) { this.classCount = classCount; }
        public int getObjectPropertyCount() { return objectPropertyCount; }
        public void setObjectPropertyCount(int objectPropertyCount) { this.objectPropertyCount = objectPropertyCount; }
    }

    // For ValidationPanel.tsx
    public static class ValidationResultDto {
        // ... (add all fields from ValidationResult in types.ts)
        private boolean isValid;
        private List<String> orphanClasses;
        // ... Getters & Setters
        public boolean getIsValid() { return isValid; }
        public void setIsValid(boolean isValid) { this.isValid = isValid; }
        public List<String> getOrphanClasses() { return orphanClasses; }
        public void setOrphanClasses(List<String> orphanClasses) { this.orphanClasses = orphanClasses; }
    }
}