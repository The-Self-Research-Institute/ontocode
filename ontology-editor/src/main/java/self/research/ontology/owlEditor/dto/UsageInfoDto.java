package self.research.ontology.owlEditor.dto;

import java.util.List;

public class UsageInfoDto {
    private String classIri;
    private int totalUsages;
    private List<AxiomUsage> usages;

    public static class AxiomUsage {
        private String category;
        private String description;
        private String relatedEntity;
        private String axiomType;

        public AxiomUsage() {}

        public AxiomUsage(String category, String description, String relatedEntity, String axiomType) {
            this.category = category;
            this.description = description;
            this.relatedEntity = relatedEntity;
            this.axiomType = axiomType;
        }

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getRelatedEntity() { return relatedEntity; }
        public void setRelatedEntity(String relatedEntity) { this.relatedEntity = relatedEntity; }
        public String getAxiomType() { return axiomType; }
        public void setAxiomType(String axiomType) { this.axiomType = axiomType; }
    }

    public UsageInfoDto() {}

    public UsageInfoDto(String classIri, int totalUsages, List<AxiomUsage> usages) {
        this.classIri = classIri;
        this.totalUsages = totalUsages;
        this.usages = usages;
    }

    public String getClassIri() { return classIri; }
    public void setClassIri(String classIri) { this.classIri = classIri; }
    public int getTotalUsages() { return totalUsages; }
    public void setTotalUsages(int totalUsages) { this.totalUsages = totalUsages; }
    public List<AxiomUsage> getUsages() { return usages; }
    public void setUsages(List<AxiomUsage> usages) { this.usages = usages; }
}