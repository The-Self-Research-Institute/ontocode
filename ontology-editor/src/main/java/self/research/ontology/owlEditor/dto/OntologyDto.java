package self.research.ontology.owlEditor.dto;

import java.util.List;

public class OntologyDtos {

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

    public static class TreeNode {
        private String id;
        private String label;
        private boolean hasChildren;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        
        public boolean isHasChildren() { return hasChildren; }
        public void setHasChildren(boolean hasChildren) { this.hasChildren = hasChildren; }
    }

    public static class TreeNodeWithParent extends TreeNode {
        private String parent;

        public String getParent() { return parent; }
        public void setParent(String parent) { this.parent = parent; }
    }

    public static class PropertyDto {
        private String iri;
        private String label;
        private String localName;
        private String type; // "ObjectProperty" or "DataProperty"
        private List<String> domains;
        private List<String> ranges;

        public String getIri() { return iri; }
        public void setIri(String iri) { this.iri = iri; }
        
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        
        public String getLocalName() { return localName; }
        public void setLocalName(String localName) { this.localName = localName; }
        
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public List<String> getDomains() { return domains; }
        public void setDomains(List<String> domains) { this.domains = domains; }
        
        public List<String> getRanges() { return ranges; }
        public void setRanges(List<String> ranges) { this.ranges = ranges; }
    }

    public static class IndividualDto {
        private String iri;
        private String label;
        private String localName;
        private List<String> types;

        public String getIri() { return iri; }
        public void setIri(String iri) { this.iri = iri; }
        
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        
        public String getLocalName() { return localName; }
        public void setLocalName(String localName) { this.localName = localName; }
        
        public List<String> getTypes() { return types; }
        public void setTypes(List<String> types) { this.types = types; }
    }

    public static class SimpleEntityDto {
        private String iri;
        private String label;
        private String localName;

        public String getIri() { return iri; }
        public void setIri(String iri) { this.iri = iri; }
        
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        
        public String getLocalName() { return localName; }
        public void setLocalName(String localName) { this.localName = localName; }
    }
}