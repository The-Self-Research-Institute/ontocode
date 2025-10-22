package self.research.ontocode.gateway.utils; // Adjust package as per your project

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Key;
import java.util.Date;
import java.util.List; // Import for casting claims.get("roles")

@Component
public class GatewayAuthFilter extends AbstractGatewayFilterFactory<GatewayAuthFilter.Config> {

    @Value("${jwt.secret}")
    private String SECRET_KEY; // Must match the secret in Auth Microservice

    public GatewayAuthFilter() {
        super(Config.class);
    }

    public static class Config {
        // Put configuration properties here if needed for this filter
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            System.out.println('H');
            if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                return this.onError(exchange, "Missing Authorization header", HttpStatus.UNAUTHORIZED);
            }

            String authHeader = request.getHeaders().get(HttpHeaders.AUTHORIZATION).get(0);
            if (!authHeader.startsWith("Bearer ")) {
                return this.onError(exchange, "Invalid Authorization header format", HttpStatus.UNAUTHORIZED);
            }

            String token = authHeader.substring(7); // Extract the JWT token

            try {
                // Validate the JWT token
                Claims claims = Jwts.parser().setSigningKey(getSigningKey()).build()
                        .parseClaimsJws(token).getBody();

                // Check expiration
                if (claims.getExpiration().before(new Date())) {
                    return this.onError(exchange, "Token expired", HttpStatus.UNAUTHORIZED);
                }

                // Add user information from JWT claims to request headers
                // These headers can then be read by downstream microservices
                ServerHttpRequest mutatedRequest = request.mutate()
                        .header("X-User-Id", claims.getSubject()) // Subject is typically the username
                        // Assuming "roles" claim is a List of Strings in your JWT
                        .header("X-User-Roles", String.join(",", (List<String>) claims.get("roles")))
                        .build();

                // Continue the filter chain with the mutated request
                return chain.filter(exchange.mutate().request(mutatedRequest).build());

            } catch (Exception e) {
                // Log the exception for debugging
                System.err.println("JWT Validation Error: " + e.getMessage());
                return this.onError(exchange, "Invalid/Expired JWT Token", HttpStatus.UNAUTHORIZED);
            }
        };
    }

    private Mono<Void> onError(ServerWebExchange exchange, String err, HttpStatus httpStatus) {
        exchange.getResponse().setStatusCode(httpStatus);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");
        // Optionally, write a more descriptive error body
        // String errorBody = "{\"error\": \"" + err + "\"}";
        // DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(errorBody.getBytes());
        // return exchange.getResponse().writeWith(Mono.just(buffer));
        return exchange.getResponse().setComplete();
    }

    private Key getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(SECRET_KEY);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}