package self.research.ontology.owlEditor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class OntologyDto {

    public static class TreeNode {
        private String id;
        private String label;
        private String description;
        private String parent;

        private List<String> subClassOf;
        private List<TreeNode> children;
        private Boolean hasChildren;
        private Map<String, String> annotations;

        private List<Map<String, String>> equivalentClasses;

        private List<Map<String, String>> disjointWith;

        private List<Map<String, String>> restrictions;

        private String sourceOntology;

        private List<ClassExpressionDto> classExpressions;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getParent() { return parent; }
        public void setParent(String parent) { this.parent = parent; }
        public List<String> getSubClassOf() { return subClassOf; }
        public void setSubClassOf(List<String> subClassOf) { this.subClassOf = subClassOf; }
        public List<TreeNode> getChildren() { return children; }
        public void setChildren(List<TreeNode> children) { this.children = children; }
        public Boolean getHasChildren() { return hasChildren; }
        public void setHasChildren(Boolean hasChildren) { this.hasChildren = hasChildren; }
        public Map<String, String> getAnnotations() { return annotations; }
        public void setAnnotations(Map<String, String> annotations) { this.annotations = annotations; }
        public List<Map<String, String>> getEquivalentClasses() { return equivalentClasses; }
        public void setEquivalentClasses(List<Map<String, String>> equivalentClasses) { this.equivalentClasses = equivalentClasses; }
        public List<Map<String, String>> getDisjointWith() { return disjointWith; }
        public void setDisjointWith(List<Map<String, String>> disjointWith) { this.disjointWith = disjointWith; }
        public List<Map<String, String>> getRestrictions() { return restrictions; }
        public void setRestrictions(List<Map<String, String>> restrictions) { this.restrictions = restrictions; }
        public String getSourceOntology() { return sourceOntology; }
        public void setSourceOntology(String sourceOntology) { this.sourceOntology = sourceOntology; }
        public List<ClassExpressionDto> getClassExpressions() { return classExpressions; }
        public void setClassExpressions(List<ClassExpressionDto> classExpressions) { this.classExpressions = classExpressions; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClassExpressionDto {

        private String id;

        private String expressionType;

        private String axiomType;

        private List<Map<String, String>> operands;

        private String definition;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getExpressionType() { return expressionType; }
        public void setExpressionType(String expressionType) { this.expressionType = expressionType; }
        public String getAxiomType() { return axiomType; }
        public void setAxiomType(String axiomType) { this.axiomType = axiomType; }
        public List<Map<String, String>> getOperands() { return operands; }
        public void setOperands(List<Map<String, String>> operands) { this.operands = operands; }
        public String getDefinition() { return definition; }
        public void setDefinition(String definition) { this.definition = definition; }
    }

    public static class PropertyDto {
        private String id;
        private String label;
        private String type;
        private List<String> domains;
        private List<String> ranges;
        private List<String> superProperties;
        private List<String> characteristics;
        private Map<String, String> annotations;

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

    public static class IndividualDto {
        private String id;
        private String label;
        private List<String> types;
        private Map<String, String> annotations;

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

    public static class OntologyPrefixDto {
        private String prefix;
        private String namespace;

        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
    }

    public static class OntologyStatisticsDto {

        private int classCount;
        private int objectPropertyCount;

        public int getClassCount() { return classCount; }
        public void setClassCount(int classCount) { this.classCount = classCount; }
        public int getObjectPropertyCount() { return objectPropertyCount; }
        public void setObjectPropertyCount(int objectPropertyCount) { this.objectPropertyCount = objectPropertyCount; }
    }

    public static class ValidationResultDto {

        private boolean isValid;
        private List<String> orphanClasses;

        public boolean getIsValid() { return isValid; }
        public void setIsValid(boolean isValid) { this.isValid = isValid; }
        public List<String> getOrphanClasses() { return orphanClasses; }
        public void setOrphanClasses(List<String> orphanClasses) { this.orphanClasses = orphanClasses; }
    }
}