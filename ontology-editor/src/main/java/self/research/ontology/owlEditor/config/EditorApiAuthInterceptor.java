package self.research.ontology.owlEditor.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.crypto.SecretKey;
import java.util.List;

/**
 * Validates JWT signature on mutating API requests when {@code ontocode.editor.require-jwt} is enabled.
 * Desktop localhost and read-only endpoints are exempt.
 */
@Component
public class EditorApiAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(EditorApiAuthInterceptor.class);
    private static final AntPathMatcher PATH = new AntPathMatcher();

    private static final List<String> PUBLIC_PATTERNS = List.of(
            "/actuator/**",
            "/api/auth/**",
            "/ws/**"
    );

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${ontocode.editor.require-jwt:false}")
    private boolean requireJwt;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!requireJwt || jwtSecret == null || jwtSecret.isBlank()) {
            return true;
        }

        if (desktopMode) {
            String remote = request.getRemoteAddr();
            if ("127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote)) {
                return true;
            }
        }

        String method = request.getMethod();
        // OPTIONS passes through so CORS preflight works; all other methods require auth below.
        if (HttpMethod.OPTIONS.matches(method)) {
            return true;
        }

        String path = request.getRequestURI();
        for (String pattern : PUBLIC_PATTERNS) {
            if (PATH.match(pattern, path)) {
                return true;
            }
        }

        // Large-file load tests may POST directly to :8083 with ownerEmail (bypass gateway).
        if (HttpMethod.POST.matches(method) && path.contains("/api/ontology/upload/")
                && request.getParameter("ownerEmail") != null && !request.getParameter("ownerEmail").isBlank()) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("Editor auth required: {} {}", method, path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Authentication required\"}");
            return false;
        }

        try {
            byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
            SecretKey key = Keys.hmacShaKeyFor(keyBytes);
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(authHeader.substring(7).trim())
                    .getPayload();
            if (claims.getSubject() == null || claims.getSubject().isBlank()) {
                throw new IllegalArgumentException("missing subject");
            }
            request.setAttribute("jwtEmail", claims.getSubject());
            return true;
        } catch (Exception e) {
            log.debug("Invalid JWT for {} {}: {}", method, path, e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid or expired token\"}");
            return false;
        }
    }
}
