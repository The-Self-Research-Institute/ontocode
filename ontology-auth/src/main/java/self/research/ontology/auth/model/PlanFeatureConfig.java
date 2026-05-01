package self.research.ontology.auth.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Document(collection = "plan_feature_configs")
public class PlanFeatureConfig {

    @Id
    private String id;

    @Indexed(unique = true)
    private String planId;  // FREE, PRO, ENTERPRISE

    private List<String> features;
    private List<String> limitations;
    private LocalDateTime updatedAt;

    public PlanFeatureConfig() {}

    public PlanFeatureConfig(String planId, List<String> features, List<String> limitations) {
        this.planId = planId;
        this.features = features;
        this.limitations = limitations != null ? limitations : List.of();
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }

    public List<String> getFeatures() { return features; }
    public void setFeatures(List<String> features) { this.features = features; }

    public List<String> getLimitations() { return limitations; }
    public void setLimitations(List<String> limitations) { this.limitations = limitations; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
