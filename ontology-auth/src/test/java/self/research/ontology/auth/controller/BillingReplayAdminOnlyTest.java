package self.research.ontology.auth.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import self.research.ontology.auth.service.StripeService;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class BillingReplayAdminOnlyTest {

    @Configuration
    @EnableMethodSecurity
    static class Wiring {
        @Bean
        BillingReplayController controller() {
            return new BillingReplayController();
        }
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static BillingReplayController controller() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(Wiring.class);
        context.getBeanFactory().registerSingleton("stripeService", mock(StripeService.class));
        context.getBeanFactory().registerSingleton("mongoTemplate", mock(MongoTemplate.class));
        context.refresh();
        return context.getBean(BillingReplayController.class);
    }

    @Test
    void anOrdinaryUserCannotReplayAnInvoice() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "someone@x.org", null, AuthorityUtils.createAuthorityList("ROLE_USER")));

        assertThrows(AccessDeniedException.class, () -> controller().replayInvoice("in_123"));
    }
}
