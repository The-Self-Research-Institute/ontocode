package self.research.ontology.auth.model;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record StripeUnmatchedInvoice(
        String id,
        String invoiceId,
        String stripeCustomerId,
        String stripeSubscriptionId,
        String customerEmail,
        String discoveredEmail,
        Long amountPaid,
        Long amountDue,
        String currency,
        String hostedInvoiceUrl,
        LocalDateTime recordedAt,
        String status) {
}
