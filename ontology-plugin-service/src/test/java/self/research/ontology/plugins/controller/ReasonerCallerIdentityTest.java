package self.research.ontology.plugins.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import self.research.ontology.plugins.config.JwtAuthenticationFilter;
import self.research.ontology.plugins.config.RequestUserIds;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReasonerCallerIdentityTest {

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest currentRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private String resolve(String claimed) {
        return RequestUserIds.resolve(claimed);
    }

    @Test
    void theVerifiedUserIdWinsOverTheOneTheClientSent() {
        currentRequest().setAttribute(JwtAuthenticationFilter.AUTHENTICATED_USER_ID_ATTRIBUTE, "u-owner");

        assertEquals("u-owner", resolve("u-victim"));
    }

    @Test
    void aSignedInUserWhoseTokenHasNoUserIdNeverGetsTheClientSuppliedOne() {
        currentRequest();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("owner@x.org", null, List.of()));

        assertEquals("owner@x.org", resolve("u-victim"));
    }

    @Test
    void withoutAnySignInTheClientValueIsKeptForTheDesktopApp() {
        currentRequest();

        assertEquals("desktop-user", resolve("desktop-user"));
    }
}
