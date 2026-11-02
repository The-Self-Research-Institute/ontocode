package self.research.ontology.owlEditor.controller;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.service.RuleExecutionPresenter;
import self.research.ontology.owlEditor.service.RuleRunAccess;
import self.research.ontology.owlEditor.service.RuleRunStore;
import self.research.ontology.owlEditor.util.JwtIdentityExtractor;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleRunAxiomsControllerTest {

    private static final OWLDataFactory DF = OWLManager.getOWLDataFactory();
    private static final SecretKey KEY = Keys.hmacShaKeyFor("0123456789abcdef0123456789abcdef".getBytes());

    private RuleRunStore store;
    private RuleRunAxiomsController controller;
    private String publicRun;
    private String draftRun;

    @BeforeEach
    void setUp() {
        JwtIdentityExtractor.requireSignature(KEY);
        ProjectAccessService projects = mock(ProjectAccessService.class);
        when(projects.hasProjectAccess(anyString(), anyString())).thenReturn(false);
        when(projects.hasProjectAccess(eq("p"), eq("ann@x.org"))).thenReturn(true);
        when(projects.hasProjectAccess(eq("p"), eq("bob@x.org"))).thenReturn(true);
        when(projects.hasProjectAccess(eq("q"), eq("ann@x.org"))).thenReturn(true);
        store = new RuleRunStore(Clock.systemUTC(), Duration.ofMinutes(15), 5, 50, 1000, 1000);
        controller = new RuleRunAxiomsController(store, new RuleRunAccess(projects, false));
        publicRun = store.put("p", "ann", false, conclusions(120), structure(7));
        draftRun = store.put("p", "ann", true, conclusions(10), List.of());
    }

    @AfterEach
    void tearDown() {
        JwtIdentityExtractor.requireSignature(null);
    }

    private static List<OWLAxiom> conclusions(int n) {
        List<OWLAxiom> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(DF.getOWLClassAssertionAxiom(DF.getOWLClass(IRI.create("http://e.org/Cls" + (i % 2))),
                    DF.getOWLNamedIndividual(IRI.create("http://e.org/person" + i))));
        }
        return out;
    }

    private static List<OWLAxiom> structure(int n) {
        List<OWLAxiom> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(DF.getOWLSubClassOfAxiom(DF.getOWLClass(IRI.create("http://e.org/S" + i)),
                    DF.getOWLClass(IRI.create("http://e.org/Top"))));
        }
        return out;
    }

    private static MockHttpServletRequest as(String userId, String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (userId != null) {
            request.addHeader("Authorization", "Bearer "
                    + Jwts.builder().claim("userId", userId).claim("email", email).signWith(KEY).compact());
        }
        return request;
    }

    private ResponseEntity<Map<String, Object>> get(String project, String run, int offset, int limit, String kind, String q,
                                                    boolean draft, MockHttpServletRequest request) {
        return controller.axioms(project, run, offset, limit, kind, q, draft, request);
    }

    private ResponseEntity<Map<String, Object>> ann(String run, int offset, int limit, String kind, String q) {
        return get("p", run, offset, limit, kind, q, false, as("ann", "ann@x.org"));
    }

    @Test
    void unauthenticatedGets401() {
        assertEquals(401, get("p", publicRun, 0, 50, "conclusions", null, false, as(null, null)).getStatusCode().value());
    }

    @Test
    void userWithoutProjectAccessGets403() {
        assertEquals(403, get("p", publicRun, 0, 50, "conclusions", null, false, as("eve", "eve@x.org")).getStatusCode().value());
    }

    @Test
    void runIdFromAnotherProjectIsGone() {
        ResponseEntity<Map<String, Object>> response = get("q", publicRun, 0, 50, "conclusions", null, false, as("ann", "ann@x.org"));
        assertEquals(410, response.getStatusCode().value());
        assertEquals("This result has expired. Run Execute again.", response.getBody().get("message"));
    }

    @Test
    void runIsGoneForAnotherProjectMemberEvenWithTheRunId() {
        assertEquals(410, get("p", publicRun, 0, 50, "conclusions", null, false, as("bob", "bob@x.org")).getStatusCode().value());
    }

    @Test
    void draftRunIsGoneForOtherUsersAndForMismatchedScope() {
        assertEquals(410, get("p", draftRun, 0, 50, "conclusions", null, true, as("bob", "bob@x.org")).getStatusCode().value());
        assertEquals(410, get("p", draftRun, 0, 50, "conclusions", null, false, as("ann", "ann@x.org")).getStatusCode().value());
        assertEquals(200, get("p", draftRun, 0, 50, "conclusions", null, true, as("ann", "ann@x.org")).getStatusCode().value());
        assertEquals(410, get("p", publicRun, 0, 50, "conclusions", null, true, as("ann", "ann@x.org")).getStatusCode().value());
    }

    @Test
    void unknownRunIsGone() {
        assertEquals(410, ann("no-such-run", 0, 50, "conclusions", null).getStatusCode().value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagingMath() {
        Map<String, Object> first = ann(publicRun, 0, 50, "conclusions", null).getBody();
        assertEquals(120, first.get("total"));
        assertEquals(50, ((List<?>) first.get("axioms")).size());
        assertEquals("conclusions", first.get("kind"));
        Map<String, Object> last = ann(publicRun, 100, 50, "conclusions", null).getBody();
        assertEquals(20, ((List<?>) last.get("axioms")).size());
        assertEquals(100, last.get("offset"));
        Map<String, Object> past = ann(publicRun, 500, 50, "conclusions", null).getBody();
        assertTrue(((List<?>) past.get("axioms")).isEmpty());
        assertEquals(120, past.get("total"));
        assertEquals(7, ann(publicRun, 0, 50, "structure", null).getBody().get("total"));
        Map<String, Object> item = ((List<Map<String, Object>>) first.get("axioms")).get(0);
        assertEquals("conclusion", item.get("category"));
        assertTrue(item.containsKey("subjectIri") && item.containsKey("description"));
    }

    @Test
    void invalidParametersAreRejected() {
        assertEquals(400, ann(publicRun, -1, 50, "conclusions", null).getStatusCode().value());
        assertEquals(400, ann(publicRun, 0, 0, "conclusions", null).getStatusCode().value());
        assertEquals(400, ann(publicRun, 0, 501, "conclusions", null).getStatusCode().value());
        assertEquals(400, ann(publicRun, 0, 50, "other", null).getStatusCode().value());
    }

    @Test
    void searchIsCaseInsensitiveAndAppliedBeforePaging() {
        Map<String, Object> body = ann(publicRun, 0, 5, "conclusions", "PERSON11").getBody();
        assertEquals(11, ((Number) body.get("total")).intValue());
        assertEquals(5, ((List<?>) body.get("axioms")).size());
        assertEquals(60, ann(publicRun, 0, 500, "conclusions", "cls1").getBody().get("total"));
        assertEquals(0, ((Number) ann(publicRun, 0, 50, "conclusions", "zzz-nothing").getBody().get("total")).intValue());
    }

    @Test
    void countsAreRealTotalsWhileFirstPageStaysCapped() {
        List<OWLAxiom> derived = new ArrayList<>(conclusions(3000));
        derived.addAll(structure(5));
        RuleExecutionPresenter.Full full = RuleExecutionPresenter.partition(derived, Set.of());
        RuleExecutionPresenter.Presented presented = RuleExecutionPresenter.limited(full, RuleExecutionPresenter.MAX_INFERRED);
        assertEquals(3000, full.conclusions().size());
        assertEquals(3000, presented.conclusionCount());
        assertEquals(5, presented.structureCount());
        assertEquals(3000, presented.ordered().size());
        RuleExecutionPresenter.Presented old = RuleExecutionPresenter.present(derived, Set.of(), RuleExecutionPresenter.MAX_INFERRED);
        assertEquals(presented.ordered(), old.ordered());
        RuleRunStore small = new RuleRunStore(Clock.systemUTC(), Duration.ofMinutes(15), 5, 50, 100, 100);
        String id = small.put("p", "ann", false, full.conclusions(), full.structure());
        assertTrue(small.find(id).orElseThrow().capped());
        assertEquals(100, small.find(id).orElseThrow().conclusions().size());
    }

    @Test
    void ownerGetsTheirPublicRun() {
        assertEquals(200, ann(publicRun, 0, 50, "conclusions", null).getStatusCode().value());
    }

    @Test
    void forbiddenUserCannotProbeForExistence() {
        assertEquals(403, get("p", "no-such-run", 0, 50, "conclusions", null, false, as("eve", "eve@x.org")).getStatusCode().value());
    }
}
