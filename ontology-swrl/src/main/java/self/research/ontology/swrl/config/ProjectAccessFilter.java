package self.research.ontology.swrl.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProjectAccessFilter extends OncePerRequestFilter {

    private static final AntPathMatcher PATH = new AntPathMatcher();
    private static final String SWRL_PROJECT_ROUTE = "/api/swrl/{projectId}/**";
    private static final String EDITOR_ACCESS_PROBE = "/api/ontology/{projectId}/draft/settings";
    private static final long ALLOWED_FOR_MILLIS = 30_000;

    private final RestTemplate editor;
    private final boolean enabled;
    private final Map<String, Long> allowedUntil = new ConcurrentHashMap<>();

    @Autowired
    public ProjectAccessFilter(RestTemplateBuilder builder,
                               @Value("${ontology.editor.service.url}") String editorServiceUrl,
                               @Value("${swrl.access-check.enabled:true}") boolean enabled) {
        this(builder.rootUri(editorServiceUrl).setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(15)).build(), enabled);
    }

    public ProjectAccessFilter(RestTemplate editor, boolean enabled) {
        this.editor = editor;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !PATH.match(SWRL_PROJECT_ROUTE, request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            reject(response, HttpStatus.UNAUTHORIZED, "Authentication required");
            return;
        }
        String projectId = PATH.extractUriTemplateVariables(SWRL_PROJECT_ROUTE, request.getRequestURI()).get("projectId");
        String key = fingerprint(authorization) + ":" + projectId;
        Long until = allowedUntil.get(key);
        if (until != null && until > System.currentTimeMillis()) {
            chain.doFilter(request, response);
            return;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authorization);
        try {
            editor.exchange(EDITOR_ACCESS_PROBE, HttpMethod.GET, new HttpEntity<>(headers), Void.class, projectId);
        } catch (HttpStatusCodeException e) {
            boolean unauthenticated = e.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value();
            reject(response, unauthenticated ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN,
                    unauthenticated ? "Authentication required" : "You do not have access to this project");
            return;
        } catch (RestClientException e) {
            reject(response, HttpStatus.SERVICE_UNAVAILABLE, "Project access could not be verified");
            return;
        }
        allowedUntil.put(key, System.currentTimeMillis() + ALLOWED_FOR_MILLIS);
        if (allowedUntil.size() > 10_000) {
            allowedUntil.values().removeIf(expiry -> expiry < System.currentTimeMillis());
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private static String fingerprint(String authorization) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(authorization.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
