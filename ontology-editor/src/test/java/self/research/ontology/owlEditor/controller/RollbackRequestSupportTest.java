package self.research.ontology.owlEditor.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RollbackRequestSupportTest {

    private static final String P = "proj";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(new byte[32]);

    private WorkspaceOwnershipService ownership;
    private RollbackRequestSupport support;
    private final List<HistoryChange> publicChange = List.of(new HistoryChange());

    @BeforeEach
    void setUp() {
        JwtIdentityExtractor.requireSignature(null);
        ownership = mock(WorkspaceOwnershipService.class);
        support = new RollbackRequestSupport(ownership);
    }

    @AfterEach
    void reset() {
        JwtIdentityExtractor.requireSignature(null);
    }

    private static MockHttpServletRequest unsigned(String json) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    private static MockHttpServletRequest signed(Map<String, Object> claims) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + Jwts.builder().claims(claims).signWith(KEY).compact());
        return request;
    }

    @Test
    void aViewerIsRefused() {
        when(ownership.isViewerInProject("u1", P)).thenReturn(true);

        ResponseEntity<Map<String, Object>> denied = support.denyIfNotAllowed(publicChange, P, unsigned("{\"userId\":\"u1\"}"));

        assertEquals(403, denied.getStatusCode().value());
    }

    @Test
    void whenSignaturesAreRequiredATokenWithoutAnAccountIdIsRefusedInsteadOfSkippingTheRoleCheck() {
        JwtIdentityExtractor.requireSignature(KEY);

        ResponseEntity<Map<String, Object>> denied = support.denyIfNotAllowed(publicChange, P, signed(Map.of("email", "viewer@x.org")));

        assertEquals(403, denied.getStatusCode().value());
    }

    @Test
    void whenSignaturesAreRequiredNoTokenAtAllIsNotSignedIn() {
        JwtIdentityExtractor.requireSignature(KEY);

        assertEquals(401, support.denyIfNotAllowed(publicChange, P, new MockHttpServletRequest()).getStatusCode().value());
    }

    @Test
    void aSignedTokenWithAnAccountIdPassesWhenTheUserIsNotAViewer() {
        JwtIdentityExtractor.requireSignature(KEY);

        assertNull(support.denyIfNotAllowed(publicChange, P, signed(Map.of("email", "e@x.org", "userId", "u2"))));
    }

    @Test
    void withoutSignatureCheckingAndWithoutATokenTheOldLocalBehaviourStays() {
        assertNull(support.denyIfNotAllowed(publicChange, P, new MockHttpServletRequest()));
    }

    @Test
    void aRefusalNamesTheRealSkippedReasonInsteadOfNothingToUndo() {
        var skipped = new self.research.ontology.owlEditor.service.ChangeRollbackService.Item(
                "c", null, null, "skos", null, "It was changed since this edit");
        var result = new self.research.ontology.owlEditor.service.ChangeRollbackService.Result(
                200, false, false, "UNDO", "cs", List.of(), List.of(skipped), null, "Nothing to undo");

        ResponseEntity<Map<String, Object>> response = RollbackRequestSupport.toBody(result, null, null);

        assertEquals(409, response.getStatusCode().value());
        assertEquals("It was changed since this edit", response.getBody().get("message"));
        assertEquals("It was changed since this edit", response.getBody().get("error"));
    }

    @Test
    void nothingToUndoStaysWhenNothingWasSkipped() {
        var result = new self.research.ontology.owlEditor.service.ChangeRollbackService.Result(
                200, false, false, "UNDO", "cs", List.of(), List.of(), null, "Nothing to undo");

        assertEquals("Nothing to undo", RollbackRequestSupport.toBody(result, null, null).getBody().get("message"));
    }
}
