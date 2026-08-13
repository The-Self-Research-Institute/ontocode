package self.research.ontology.auth.dto;

public class BillingCheckoutRequest {
    private String planName;
    private String interval;
    private String workspaceId;

    public BillingCheckoutRequest() {}

    public BillingCheckoutRequest(String planName, String interval, String workspaceId) {
        this.planName = planName;
        this.interval = interval;
        this.workspaceId = workspaceId;
    }

    public String getPlanName() {
        return planName;
    }

    public void setPlanName(String planName) {
        this.planName = planName;
    }

    public String getInterval() {
        return interval;
    }

    public void setInterval(String interval) {
        this.interval = interval;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    @Override
    public String toString() {
        return "BillingCheckoutRequest{" +
                "planName='" + planName + '\'' +
                ", interval='" + interval + '\'' +
                ", workspaceId='" + workspaceId + '\'' +
                '}';
    }
}
