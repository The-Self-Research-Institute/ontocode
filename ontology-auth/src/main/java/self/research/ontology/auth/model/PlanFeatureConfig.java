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

    private int monthlyPrice;
    private int annualDiscountPercent;  // e.g. 17 → annualPrice = round(monthly * 0.83)

    private List<String> features;
    private List<String> limitations;
    private LocalDateTime updatedAt;

    public PlanFeatureConfig() {}

    public PlanFeatureConfig(String planId, int monthlyPrice, int annualDiscountPercent,
                             List<String> features, List<String> limitations) {
        this.planId = planId;
        this.monthlyPrice = monthlyPrice;
        this.annualDiscountPercent = annualDiscountPercent;
        this.features = features;
        this.limitations = limitations != null ? limitations : List.of();
        this.updatedAt = LocalDateTime.now();
    }

    public int computedAnnualPrice() {
        return (int) Math.round(monthlyPrice * (1 - annualDiscountPercent / 100.0));
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }

    public int getMonthlyPrice() { return monthlyPrice; }
    public void setMonthlyPrice(int monthlyPrice) { this.monthlyPrice = monthlyPrice; }

    public int getAnnualDiscountPercent() { return annualDiscountPercent; }
    public void setAnnualDiscountPercent(int annualDiscountPercent) { this.annualDiscountPercent = annualDiscountPercent; }

    public List<String> getFeatures() { return features; }
    public void setFeatures(List<String> features) { this.features = features; }

    public List<String> getLimitations() { return limitations; }
    public void setLimitations(List<String> limitations) { this.limitations = limitations; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
