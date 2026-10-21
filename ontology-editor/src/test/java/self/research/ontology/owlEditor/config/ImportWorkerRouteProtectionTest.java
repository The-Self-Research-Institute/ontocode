package self.research.ontology.owlEditor.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ImportWorkerRouteProtectionTest {

    private static final class ExposedRegistry extends InterceptorRegistry {
        List<Object> registered() {
            return getInterceptors();
        }
    }

    private InternalApiAuthInterceptor internal;
    private EditorApiAuthInterceptor editor;
    private List<Object> registered;

    @BeforeEach
    void setUp() {
        internal = mock(InternalApiAuthInterceptor.class);
        editor = mock(EditorApiAuthInterceptor.class);
        WebMvcConfig config = new WebMvcConfig(mock(PerformanceLoggingInterceptor.class),
                mock(FreeViewOnlyInterceptor.class), editor, mock(SparqlQueryContextInterceptor.class), internal,
                new CallerArgumentResolver(false));
        ExposedRegistry registry = new ExposedRegistry();
        config.addInterceptors(registry);
        registered = registry.registered();
    }

    private boolean guards(Object interceptor, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        ServletRequestPathUtils.parseAndCache(request);
        return registered.stream()
                .filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .anyMatch(mapped -> mapped.getInterceptor() == interceptor && mapped.matches(request));
    }

    @Test
    void theImportWorkerSubmitRouteNeedsTheInternalToken() {
        assertTrue(guards(internal, "/api/import-worker/submit"));
    }

    @Test
    void theImportWorkerRouteIsNotOpenToUserTokens() {
        assertFalse(guards(editor, "/api/import-worker/submit"));
    }

    @Test
    void ordinaryApiRoutesStillUseTheUserTokenCheck() {
        assertTrue(guards(editor, "/api/projects"));
        assertFalse(guards(internal, "/api/projects"));
    }

    @Test
    void theInternalRoutesStillNeedTheInternalToken() {
        assertTrue(guards(internal, "/internal/reasoning/p1/export.ttl"));
    }
}
