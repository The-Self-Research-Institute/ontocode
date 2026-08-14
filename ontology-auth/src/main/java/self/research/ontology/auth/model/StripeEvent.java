package self.research.ontology.auth.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Persisted record of processed Stripe webhook events.
 * Used for idempotency — ensures each event is processed exactly once.
 */
@Document(collection = "stripe_events")
public class StripeEvent {

    @Id
    private String id;

    @Indexed(unique = true)
    private String stripeEventId;

    private String eventType;
    private String status; // processed, failed
    private String errorMessage;

    private LocalDateTime receivedAt;
    private LocalDateTime processedAt;

    public StripeEvent() {
        this.receivedAt = LocalDateTime.now();
    }

    public StripeEvent(String stripeEventId, String eventType) {
        this.stripeEventId = stripeEventId;
        this.eventType = eventType;
        this.receivedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getStripeEventId() { return stripeEventId; }
    public void setStripeEventId(String stripeEventId) { this.stripeEventId = stripeEventId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }

    public LocalDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(LocalDateTime processedAt) { this.processedAt = processedAt; }
}
