package self.research.ontology.auth.dto;

/**
 * Response DTO for billing portal session
 */
public class BillingPortalResponse {
    private String url;

    public BillingPortalResponse() {}

    public BillingPortalResponse(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    public String toString() {
        return "BillingPortalResponse{" +
                "url='" + (url != null ? url.substring(0, Math.min(30, url.length())) + "..." : "null") + '\'' +
                '}';
    }
}
