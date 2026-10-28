package self.research.ontology.owlEditor.upload;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadBytesLimitFilterTest {

    private static UploadBytesLimitFilter filter(MockEnvironment env, UploadAdmission admission) {
        return new UploadBytesLimitFilter(new UploadSettings(env), admission);
    }

    private static MockHttpServletRequest post(String uri, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContent(body);
        return request;
    }

    private static HttpServletRequest withoutContentLength(MockHttpServletRequest request) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
    }

    private static final FilterChain DRAIN = (req, res) -> req.getInputStream().readAllBytes();

    private static SemaphoreUploadAdmission open() {
        return new SemaphoreUploadAdmission(5, 10);
    }

    @Test
    void defaultsMatchTheDocumentedLimitsPerEndpoint() {
        UploadBytesLimitFilter f = filter(new MockEnvironment(), open());
        assertEquals(1024L * 1024 * 1024, f.match("/api/ontology/upload/proj-1").limitBytes());
        assertEquals(32L * 1024 * 1024, f.match("/api/ontology/upload-chunk/proj-1").limitBytes());
        assertEquals(25L * 1024 * 1024, f.match("/api/ontology/spreadsheet-import/p/preview").limitBytes());
        assertEquals(8L * 1024 * 1024, f.match("/api/ontology/p/rules/import").limitBytes());
        assertEquals("merge", f.match("/api/projects/p/merge/analyze").name());
        assertEquals("issue", f.match("/api/v1/issues/report").name());
    }

    @Test
    void unrelatedUrlsAreNotMatched() {
        UploadBytesLimitFilter f = filter(new MockEnvironment(), open());
        assertNull(f.match("/api/ontology/p/classes"));
        assertNull(f.match("/api/ontology/p/rules/export"));
        assertNull(f.match("/api/ontology/upload-by-file-ref/p"));
    }

    @Test
    void shouldNotFilterGetRequestsAndUnmatchedPaths() {
        UploadBytesLimitFilter f = filter(new MockEnvironment(), open());
        assertTrue(f.shouldNotFilter(new MockHttpServletRequest("GET", "/api/ontology/upload/p")));
        assertTrue(f.shouldNotFilter(new MockHttpServletRequest("POST", "/api/ontology/p/classes")));
        assertFalse(f.shouldNotFilter(new MockHttpServletRequest("POST", "/api/ontology/upload/p")));
    }

    @Test
    void limitsCanBeOverriddenPerEndpointAndByLegacyName() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("ontocode.capacity.upload.max-bytes.spreadsheet", "100")
                .withProperty("ontocode.upload.max-bytes.rule-import", "50");
        UploadBytesLimitFilter f = filter(env, open());
        assertEquals(100, f.match("/api/ontology/spreadsheet-import/p/parse").limitBytes());
        assertEquals(50, f.match("/api/ontology/p/rules/import").limitBytes());
        assertEquals(1024L * 1024 * 1024, f.match("/api/ontology/upload/p").limitBytes());
    }

    @Test
    void rejectsEarlyWhenContentLengthExceedsTheLimitWithoutReadingTheBody() throws Exception {
        MockEnvironment env = new MockEnvironment().withProperty("ontocode.capacity.upload.max-bytes.spreadsheet", "100");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter(env, open()).doFilter(post("/api/ontology/spreadsheet-import/p/parse", new byte[500]), response,
                (req, res) -> reached.set(true));
        assertEquals(413, response.getStatus());
        assertFalse(reached.get());
        assertTrue(response.getContentAsString().contains("\"limitBytes\":100"));
    }

    @Test
    void bodyWithoutContentLengthIsCountedAndRejected() throws Exception {
        MockEnvironment env = new MockEnvironment().withProperty("ontocode.capacity.upload.max-bytes.spreadsheet", "100");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = post("/api/ontology/spreadsheet-import/p/parse", new byte[500]);
        filter(env, open()).doFilter(withoutContentLength(request), response, DRAIN);
        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("limitBytes"));
    }

    @Test
    void bodyExactlyAtTheLimitPasses() throws Exception {
        MockEnvironment env = new MockEnvironment().withProperty("ontocode.capacity.upload.max-bytes.spreadsheet", "100");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicLong seen = new AtomicLong();
        filter(env, open()).doFilter(withoutContentLength(post("/api/ontology/spreadsheet-import/p/parse", new byte[100])),
                response, (req, res) -> seen.set(req.getInputStream().readAllBytes().length));
        assertEquals(200, response.getStatus());
        assertEquals(100, seen.get());
    }

    @Test
    void differentEndpointsUseTheirOwnLimit() throws Exception {
        MockEnvironment env = new MockEnvironment()
                .withProperty("ontocode.capacity.upload.max-bytes.spreadsheet", "100")
                .withProperty("ontocode.capacity.upload.max-bytes.rule-import", "1000");
        UploadBytesLimitFilter f = filter(env, open());
        MockHttpServletResponse sheet = new MockHttpServletResponse();
        f.doFilter(post("/api/ontology/spreadsheet-import/p/parse", new byte[500]), sheet, DRAIN);
        MockHttpServletResponse rules = new MockHttpServletResponse();
        f.doFilter(post("/api/ontology/p/rules/import", new byte[500]), rules, DRAIN);
        assertEquals(413, sheet.getStatus());
        assertEquals(200, rules.getStatus());
    }

    @Test
    void answers429WithTheBusyBodyWhenNoPermitIsFree() throws Exception {
        SemaphoreUploadAdmission admission = new SemaphoreUploadAdmission(1, 10);
        UploadAdmission.Lease held = admission.tryAdmit();
        assertNotNull(held);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();
        filter(new MockEnvironment(), admission).doFilter(post("/api/ontology/upload/p", new byte[10]), response,
                (req, res) -> reached.set(true));
        assertEquals(429, response.getStatus());
        assertFalse(reached.get());
        String body = response.getContentAsString();
        assertTrue(body.contains("\"busy\":true"));
        assertTrue(body.contains("\"reason\":\"too_many_uploads\""));
        assertTrue(body.contains("\"retryAfterSeconds\":10"));
        assertTrue(body.contains("\"message\""));
        assertEquals("10", response.getHeader("Retry-After"));
    }

    @Test
    void permitIsReleasedAfterSuccessAndAfterAnError() throws Exception {
        SemaphoreUploadAdmission admission = new SemaphoreUploadAdmission(1, 10);
        UploadBytesLimitFilter f = filter(new MockEnvironment(), admission);
        f.doFilter(post("/api/ontology/upload/p", new byte[1]), new MockHttpServletResponse(), DRAIN);
        assertEquals(1, admission.available());
        assertThrows(IllegalStateException.class, () -> f.doFilter(post("/api/ontology/upload/p", new byte[1]),
                new MockHttpServletResponse(), (req, res) -> {
                    throw new IllegalStateException("boom");
                }));
        assertEquals(1, admission.available());
        f.doFilter(post("/api/ontology/upload/p", new byte[1]), new MockHttpServletResponse(),
                (req, res) -> {
                    throw new IOException(new UploadLimitExceededException("too big", 5));
                });
        assertEquals(1, admission.available());
    }
}
