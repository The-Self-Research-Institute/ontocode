package self.research.ontology.plugins.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;

@Slf4j
@Component("pluginJwtAuthFilter")
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_USER_ID_ATTRIBUTE = "authenticatedUserId";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    private static final String DEFAULT_INTERNAL_TOKEN = "ontocode-internal";
    private static final String CACHE_CLEAR_PATH_PREFIX = "/api/reasoner/clear-cache/";

    @Value("${ontocode.internal.token:" + DEFAULT_INTERNAL_TOKEN + "}")
    private String internalToken;

    public static final String INTERNAL_SERVICE_PRINCIPAL = "internal-service";

    @PostConstruct
    public void validateSecrets() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                "[SECURITY] jwt.secret (JWT_SECRET) must be set in the plugin service. " +
                "Generate one with: openssl rand -base64 48");
        }
        byte[] decoded;
        try {
            decoded = Decoders.BASE64.decode(jwtSecret);
        } catch (Exception e) {
            throw new IllegalStateException(
                "[SECURITY] jwt.secret is not valid Base64 in plugin service.");
        }
        if (decoded.length < 32) {
            throw new IllegalStateException(
                "[SECURITY] jwt.secret must decode to at least 256 bits (32 bytes). " +
                "Current length: " + decoded.length + " bytes.");
        }
        log.info("Plugin service JWT secret validated \u2014 {} bytes.", decoded.length);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (desktopMode) {
            filterChain.doFilter(request, response);
            return;
        }

        String presentedInternalToken = request.getHeader("X-Ontocode-Internal-Token");
        boolean onCacheClearPath = request.getRequestURI() != null
                && request.getRequestURI().contains(CACHE_CLEAR_PATH_PREFIX);
        if (onCacheClearPath && presentedInternalToken != null && internalToken != null
                && MessageDigest.isEqual(
                        internalToken.getBytes(StandardCharsets.UTF_8),
                        presentedInternalToken.getBytes(StandardCharsets.UTF_8))) {
            UserDetails internalUser = User.builder()
                    .username(INTERNAL_SERVICE_PRINCIPAL)
                    .password("")
                    .authorities(new ArrayList<>())
                    .build();
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(internalUser, null, internalUser.getAuthorities()));
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            try {

                byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
                SecretKey key = Keys.hmacShaKeyFor(keyBytes);

                Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

                String email = claims.getSubject();

                if (email != null) {
                    UserDetails userDetails = User.builder()
                        .username(email)
                        .password("")
                        .authorities(new ArrayList<>())
                        .build();

                    UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.debug("JWT authentication successful for user: {}", email);
                    String userId = claims.get("userId", String.class);
                    if (userId != null && !userId.isBlank()) {
                        request.setAttribute(AUTHENTICATED_USER_ID_ATTRIBUTE, userId);
                    }
                }
            } catch (Exception e) {
                log.error("JWT authentication failed: {}", e.getMessage());
                SecurityContextHolder.clearContext();
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
