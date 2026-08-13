package self.research.ontocode.gateway.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.List;

@Component
public class GatewayAuthFilter implements GlobalFilter, Ordered {

    private static final AntPathMatcher PATH = new AntPathMatcher();
    private static final List<String> PUBLIC_API_PATTERNS = List.of(
            "/api/auth/login",
            "/api/auth/signup",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/verify",
            "/api/auth/verify-email",
            "/api/auth/resend-verification",
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/api/auth/desktop/**",
            "/api/downloads/**",
            "/api/plugins/*/download",
            "/actuator/health",
            "/api/invitations/details/**",
            "/api/invitations/request-resend/**",
            "/api/billing/plans",
            "/api/billing/webhook",
            "/api/v1/issues/report",
            "/api/maintenance/status"
    );

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${ontocode.gateway.require-jwt:true}")
    private boolean requireJwt;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!requireJwt || jwtSecret == null || jwtSecret.isBlank()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith("/api/")) {
            return chain.filter(exchange);
        }

        if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }

        for (String pattern : PUBLIC_API_PATTERNS) {
            if (PATH.match(pattern, path)) {
                return chain.filter(exchange);
            }
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7).trim();
        try {
            byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
            Key key = Keys.hmacShaKeyFor(keyBytes);
            Claims claims = Jwts.parser()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getPayload();
            if (claims.getSubject() == null || claims.getSubject().isBlank()) {
                return unauthorized(exchange, "Invalid token: missing subject");
            }
        } catch (Exception e) {
            return unauthorized(exchange, "Invalid or expired token");
        }

        return chain.filter(exchange);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
        String body = "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public int getOrder() {
        return -50;
    }
}
