package self.research.ontology.auth.service;

import com.stripe.model.Invoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.EmailLogRepository;
import self.research.ontology.auth.repository.StripeUnmatchedInvoiceRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentFailedHandlerTest {

    UserRepository users;
    WorkspaceService workspaces;
    EmailService email;
    StripeService service;
    User user;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        workspaces = mock(WorkspaceService.class);
        email = mock(EmailService.class);
        service = new StripeService(users, mock(WorkspaceRepository.class), workspaces, email,
                mock(EmailLogRepository.class), mock(StripeUnmatchedInvoiceRepository.class));
        user = new User();
        user.setId("u1");
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setStripeCustomerId("cus_1");
        user.setStripeSubscriptionId("sub_1");
        user.setSubscriptionStatus("active");
        when(users.findByStripeCustomerId("cus_1")).thenReturn(Optional.of(user));
    }

    private Invoice invoice(String subscriptionId) {
        Invoice invoice = new Invoice();
        invoice.setId("in_1");
        invoice.setCustomer("cus_1");
        invoice.setSubscription(subscriptionId);
        invoice.setBillingReason("subscription_cycle");
        return invoice;
    }

    @Test
    void aFailedPaymentForTheCurrentSubscriptionMarksItPastDueAndSyncsWorkspaces() {
        when(users.markPastDueIfCurrentSubscription("u1", "sub_1")).thenReturn(true);
        User fresh = new User();
        fresh.setId("u1");
        fresh.setUsername("alice");
        fresh.setSubscriptionStatus("past_due");
        when(users.findById("u1")).thenReturn(Optional.of(fresh));

        service.handleInvoicePaymentFailed(invoice("sub_1"));

        verify(users).markPastDueIfCurrentSubscription("u1", "sub_1");
        verify(workspaces).syncWorkspacesToOwnerPlan(fresh);
        verify(users, never()).save(any());
    }

    @Test
    void aFailedPaymentForASubscriptionThatIsNoLongerCurrentChangesNothingAndSendsNoEmail() {
        when(users.markPastDueIfCurrentSubscription("u1", "sub_1")).thenReturn(false);

        service.handleInvoicePaymentFailed(invoice("sub_1"));

        verify(users, never()).save(any());
        verify(workspaces, never()).syncWorkspacesToOwnerPlan(any());
        verify(email, never()).sendPaymentFailedEmail(any(), any(), any(), any(), any());
    }
}
