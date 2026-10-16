package self.research.ontology.swrl.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import self.research.ontology.swrl.service.SwrlHistoryReporter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SwrlHistoryFilterTest {

    private SwrlHistoryReporter reporter;
    private SwrlHistoryFilter filter;
    private FilterChain chain;
    private boolean[] ranRequest;
    private boolean[] reportedChange;

    @BeforeEach
    void setUp() throws Exception {
        reporter = mock(SwrlHistoryReporter.class);
        filter = new SwrlHistoryFilter(reporter);
        chain = mock(FilterChain.class);
        ranRequest = new boolean[1];
        reportedChange = new boolean[1];
        doAnswer(invocation -> {
            SwrlHistoryReporter.Request request = invocation.getArgument(2);
            reportedChange[0] = request.run();
            return null;
        }).when(reporter).aroundRequest(any(), any(), any());
        doAnswer(invocation -> {
            ranRequest[0] = true;
            return null;
        }).when(chain).doFilter(any(), any());
    }

    private MockHttpServletResponse call(String method, String path, int status) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer t");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void ruleWritesAreWatchedUnderTheirProjectAndForwardTheLogin() throws Exception {
        call("POST", "/api/swrl/p1/rules", 201);
        call("PUT", "/api/swrl/p1/rules/abc", 200);
        call("DELETE", "/api/swrl/p1/rules/abc", 204);
        call("POST", "/api/swrl/p1/rules/batch", 201);
        call("POST", "/api/swrl/p1/rules/import", 200);
        call("POST", "/api/swrl/p1/rules/abc/duplicate", 201);

        verify(reporter, org.mockito.Mockito.times(6)).aroundRequest(eq("p1"), eq("Bearer t"), any());
    }

    @Test
    void readsExecutionsAndRuleTestsAreNotWatched() throws Exception {
        call("GET", "/api/swrl/p1/rules", 200);
        call("POST", "/api/swrl/p1/execute", 200);
        call("POST", "/api/swrl/p1/rules/abc/test", 200);
        call("POST", "/api/swrl/p1/validate", 200);

        verify(reporter, never()).aroundRequest(any(), any(), any());
        assertTrue(ranRequest[0]);
    }

    @Test
    void aRequestThatFailedReportsNothing() throws Exception {
        call("POST", "/api/swrl/p1/rules", 400);

        assertTrue(ranRequest[0]);
        assertFalse(reportedChange[0]);
        assertEquals(400, call("POST", "/api/swrl/p1/rules", 400).getStatus());
    }

    @Test
    void aSuccessfulWriteIsReported() throws Exception {
        call("POST", "/api/swrl/p1/rules", 201);

        assertTrue(reportedChange[0]);
    }
}
