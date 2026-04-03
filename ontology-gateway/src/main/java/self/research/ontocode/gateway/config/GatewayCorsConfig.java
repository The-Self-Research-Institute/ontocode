package self.research.ontocode.gateway.config;

import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;

@Configuration
public class GatewayCorsConfig {

    /**
     * Shared CORS configuration used by both CorsWebFilter and the error handler.
     * allowedOriginPatterns("*") + allowCredentials(true) is valid — Spring will
     * echo back the actual request Origin instead of returning a literal "*".
     */
    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Arrays.asList("*"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH", "HEAD"));
        config.setAllowedHeaders(Arrays.asList("*"));
        config.setAllowCredentials(true);
        config.setExposedHeaders(Arrays.asList("*"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * CorsWebFilter handles CORS for all normal request/response flows, including
     * OPTIONS preflight (short-circuits without forwarding to the backend).
     * Ordered before Spring Security (which is at -100) so CORS runs first.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsWebFilter corsWebFilter() {
        return new CorsWebFilter(corsConfigurationSource());
    }

    /**
     * Error handler that ensures CORS headers are present on gateway-generated
     * error responses (e.g. 504 Gateway Timeout, 502 Bad Gateway).
     * These bypass the normal filter chain, so CorsWebFilter cannot add headers.
     * Order -2 runs before Spring Boot's default handler (order -1).
     */
    @Bean
    @Order(-2)
    public ErrorWebExceptionHandler corsErrorWebExceptionHandler() {
        return (exchange, ex) -> {
            addCorsHeaders(exchange);
            ServerHttpResponse response = exchange.getResponse();
            if (!response.isCommitted()) {
                if (ex instanceof java.util.concurrent.TimeoutException
                        || (ex.getMessage() != null && ex.getMessage().contains("timeout"))) {
                    response.setStatusCode(HttpStatus.GATEWAY_TIMEOUT);
                } else {
                    response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                }
                response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                String body = "{\"error\":\"" + ((HttpStatus) response.getStatusCode()).getReasonPhrase() + "\"}";
                org.springframework.core.io.buffer.DataBuffer buf =
                        response.bufferFactory().wrap(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return response.writeWith(Mono.just(buf));
            }
            return Mono.empty();
        };
    }

    private void addCorsHeaders(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()
                || response.getStatusCode() == HttpStatus.SWITCHING_PROTOCOLS) {
            return;
        }

        HttpHeaders headers = response.getHeaders();
        String origin = exchange.getRequest().getHeaders().getOrigin();

        headers.remove(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        headers.remove(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
        headers.remove(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS);
        headers.remove(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS);
        headers.remove(HttpHeaders.ACCESS_CONTROL_MAX_AGE);
        headers.remove(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);

        if (origin != null && !origin.isEmpty()) {
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        } else {
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        }
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS, PATCH, HEAD");
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*");
        headers.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "*");
        headers.set(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
    }
}