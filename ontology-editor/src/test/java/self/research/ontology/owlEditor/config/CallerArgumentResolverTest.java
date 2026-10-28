package self.research.ontology.owlEditor.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import self.research.ontology.owlEditor.controller.GlobalExceptionHandler;
import self.research.ontology.owlEditor.service.AccessDeniedApiException;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallerArgumentResolverTest {

    @SuppressWarnings("unused")
    private void handler(@CallerEmail String ownerEmail, CallerIdentity caller, String plain, @CallerUserId String userId) {
    }

    private static MethodParameter parameter(int index) throws Exception {
        Method method = CallerArgumentResolverTest.class.getDeclaredMethod("handler", String.class,
                CallerIdentity.class, String.class, String.class);
        MethodParameter parameter = new MethodParameter(method, index);
        parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
        return parameter;
    }

    private static MockHttpServletRequest signedIn(String email, String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"email\":\"" + email + "\",\"userId\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    private static Object resolve(CallerArgumentResolver resolver, int index, MockHttpServletRequest request)
            throws Exception {
        return resolver.resolveArgument(parameter(index), null, new ServletWebRequest(request), null);
    }

    @Test
    void itOnlyHandlesAnnotatedEmailsAndTheCallerIdentityType() throws Exception {
        CallerArgumentResolver resolver = new CallerArgumentResolver(false);

        assertTrue(resolver.supportsParameter(parameter(0)));
        assertTrue(resolver.supportsParameter(parameter(1)));
        assertFalse(resolver.supportsParameter(parameter(2)));
        assertTrue(resolver.supportsParameter(parameter(3)));
    }

    @Test
    void theEmailComesFromTheTokenNotFromTheQuery() throws Exception {
        MockHttpServletRequest request = signedIn("owner@x.org", "u-1");
        request.setParameter("ownerEmail", "victim@x.org");

        assertEquals("owner@x.org", resolve(new CallerArgumentResolver(false), 0, request));
    }

    @Test
    void theCallerIdentityCarriesTheUserId() throws Exception {
        CallerIdentity caller = (CallerIdentity) resolve(new CallerArgumentResolver(false), 1,
                signedIn("owner@x.org", "u-1"));

        assertEquals("u-1", caller.userId());
        assertEquals("owner@x.org", caller.email());
    }

    @Test
    void aCallerWithoutATokenIsRefused() {
        AccessDeniedApiException refused = assertThrows(AccessDeniedApiException.class,
                () -> resolve(new CallerArgumentResolver(false), 0, new MockHttpServletRequest()));

        assertEquals(HttpStatus.UNAUTHORIZED, refused.denial().status());
    }

    @Test
    void theDesktopAppFallsBackToTheRequestParameterWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("ownerEmail", "local@desktop");

        assertEquals("local@desktop", resolve(new CallerArgumentResolver(true), 0, request));
    }

    @Test
    void theDesktopAppStillPrefersTheTokenWhenThereIsOne() throws Exception {
        MockHttpServletRequest request = signedIn("owner@x.org", "u-1");
        request.setParameter("ownerEmail", "someone@else");

        assertEquals("owner@x.org", resolve(new CallerArgumentResolver(true), 0, request));
    }

    @Test
    void aRefusalBecomesAJson401() {
        var response = new GlobalExceptionHandler().handleAccessDenied(
                new AccessDeniedApiException(self.research.ontology.owlEditor.service.AccessDenial.unauthenticated()));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertSame(Boolean.FALSE, response.getBody().get("success"));
    }

    @Test
    void theUserIdComesFromTheTokenNotFromTheQuery() throws Exception {
        MockHttpServletRequest request = signedIn("owner@x.org", "u-1");
        request.setParameter("userId", "u-victim");

        assertEquals("u-1", resolve(new CallerArgumentResolver(false), 3, request));
    }

    @Test
    void aTokenWithoutAUserIdIsAskedToSignInAgainInsteadOfUsingItsEmail() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"email\":\"old@x.org\"}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");

        AccessDeniedApiException refused = assertThrows(AccessDeniedApiException.class,
                () -> resolve(new CallerArgumentResolver(false), 3, request));
        assertEquals(HttpStatus.UNAUTHORIZED, refused.denial().status());
        assertEquals("Sign in again to continue.", refused.denial().message());
        assertEquals("old@x.org", assertDoesNotThrow(() -> resolve(new CallerArgumentResolver(false), 0, request)));
    }

    @Test
    void aUserIdIsRefusedWithoutATokenOutsideDesktop() {
        assertThrows(AccessDeniedApiException.class,
                () -> resolve(new CallerArgumentResolver(false), 3, new MockHttpServletRequest()));
    }

    @Test
    void theDesktopAppUsesTheUserIdParameterWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("userId", "desktop-user-local");

        assertEquals("desktop-user-local", resolve(new CallerArgumentResolver(true), 3, request));
    }
}
