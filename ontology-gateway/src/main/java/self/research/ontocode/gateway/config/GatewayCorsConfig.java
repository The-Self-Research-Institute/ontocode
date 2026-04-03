package self.research.ontocode.gateway.config;

import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Configuration
public class GatewayCorsConfig {

    /**
     * Global filter to remove CORS headers from backend responses
     * This runs with the highest priority (HIGHEST_PRECEDENCE) to ensure
     * backend CORS headers are stripped before gateway adds its own
     */
    @Bean
    public GlobalFilter corsHeaderCleanupFilter() {
        return (exchange, chain) -> {
            // Register a beforeCommit callback so CORS headers are added just before
            // the response is flushed to the wire. This works for BOTH successful
            // responses and error responses (backend 500, gateway errors, etc.).
            // The previous .then() approach was too late — the response was already
            // committed and isCommitted() returned true, preventing header modification.
            exchange.getResponse().beforeCommit(() -> {
                addCorsHeaders(exchange);
                return Mono.empty();
            });
            return chain.filter(exchange);
        };
    }

    /**
     * Error handler that ensures CORS headers are present on gateway-generated
     * error responses (e.g. 504 Gateway Timeout, 502 Bad Gateway).
     * These errors are produced by Netty/Spring Cloud Gateway itself and bypass
     * the GlobalFilter beforeCommit callbacks, so CORS headers must be injected
     * here before the default error handler renders the response body.
     * Order -2 runs before the default Spring Boot handler (order -1).
     */
    @Bean
    @Order(-2)
    public ErrorWebExceptionHandler corsErrorWebExceptionHandler() {
        return (exchange, ex) -> {
            addCorsHeaders(exchange);
            // Determine appropriate status code
            ServerHttpResponse response = exchange.getResponse();
            if (!response.isCommitted()) {
                if (ex instanceof java.util.concurrent.TimeoutException
                        || ex.getMessage() != null && ex.getMessage().contains("timeout")) {
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

        // Skip CORS modification for WebSocket upgrade responses and
        // already-committed responses (prevents breaking WS handshake)
        if (response.isCommitted()
                || response.getStatusCode() == HttpStatus.SWITCHING_PROTOCOLS) {
            return;
        }

        HttpHeaders headers = response.getHeaders();

        // Get the origin from request
        String origin = exchange.getRequest().getHeaders().getOrigin();

        // Remove all CORS headers that might have been added by backend services
        headers.remove(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        headers.remove(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
        headers.remove(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS);
        headers.remove(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS);
        headers.remove(HttpHeaders.ACCESS_CONTROL_MAX_AGE);
        headers.remove(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);

        // Add gateway's CORS headers
        if (origin != null && !origin.isEmpty()) {
            headers.add(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            headers.add(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        } else {
            headers.add(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            headers.add(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "false");
        }
        headers.add(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS, PATCH, HEAD");
        headers.add(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*");
        headers.add(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "*");
        headers.add(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
    }

    /**
     * Handle OPTIONS preflight requests before routing
     */
    @Bean
    public WebFilter corsPreFlightFilter() {
        return (ServerWebExchange ctx, WebFilterChain chain) -> {
            ServerHttpRequest request = ctx.getRequest();
            if (HttpMethod.OPTIONS.equals(request.getMethod())) {
                ServerHttpResponse response = ctx.getResponse();
                HttpHeaders headers = response.getHeaders();
                
                String origin = request.getHeaders().getOrigin();
                if (origin != null) {
                    headers.add("Access-Control-Allow-Origin", origin);
                    headers.add("Access-Control-Allow-Credentials", "true");
                } else {
                    headers.add("Access-Control-Allow-Origin", "*");
                    headers.add("Access-Control-Allow-Credentials", "false");
                }
                
                headers.add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH, HEAD");
                headers.add("Access-Control-Allow-Headers", "*");
                headers.add("Access-Control-Max-Age", "3600");

                response.setStatusCode(HttpStatus.OK);
                return Mono.empty();
            }
            return chain.filter(ctx);
        };
    }
}