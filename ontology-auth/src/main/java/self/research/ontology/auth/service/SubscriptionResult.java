package self.research.ontology.auth.service;

import java.util.LinkedHashMap;
import java.util.Map;

public record SubscriptionResult(String subscriptionId, String status, boolean requiresAction, String clientSecret) {

    public Map<String, Object> toResponse() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subscriptionId", subscriptionId);
        body.put("status", status);
        body.put("requiresAction", requiresAction);
        if (requiresAction && clientSecret != null) {
            body.put("clientSecret", clientSecret);
        }
        return body;
    }
}
