package self.research.ontology.auth.model;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record EmailLog(
        String key,
        String type,
        String status,
        String kind,
        Integer daysBefore,
        LocalDateTime periodEnd,
        String email,
        String userId,
        String username,
        String stripeInvoiceId,
        String stripeCustomerId,
        String stripeSubscriptionId,
        Long amountPaid,
        String currency,
        LocalDateTime createdAt,
        LocalDateTime sentAt,
        LocalDateTime failedAt,
        String errorMessage) {
}
