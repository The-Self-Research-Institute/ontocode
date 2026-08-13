package self.research.ontocode.gateway.config;

import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.util.List;

@Configuration
public class GatewayCorsConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public WebFilter corsEarlySetFilter() {
        return (exchange, chain) -> {
            String origin = exchange.getRequest().getHeaders().getOrigin();

            boolean isPreflight = HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())
                    && exchange.getRequest().getHeaders().containsKey(HttpHeaders.ORIGIN)
                    && exchange.getRequest().getHeaders().containsKey(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
            if (isPreflight) {
                ServerHttpResponse res = exchange.getResponse();
                res.setStatusCode(HttpStatus.NO_CONTENT);
                HttpHeaders h = res.getHeaders();
                h.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        (origin != null && !origin.isEmpty()) ? origin : "*");
                h.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
                h.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        "GET, POST, PUT, DELETE, OPTIONS, PATCH, HEAD");
                h.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "*");
                h.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "*");
                h.set(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
                return res.setComplete();
            }

            if (origin != null && !origin.isEmpty()) {
                HttpHeaders h = exchange.getResponse().getHeaders();
                h.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
                h.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
                h.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "*");
            }

            return chain.filter(exchange);
        };
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    public GlobalFilter corsUpstreamHeaderStripFilter() {
        return (exchange, chain) -> chain.filter(exchange).then(Mono.fromRunnable(() -> {
            ServerHttpResponse response = exchange.getResponse();
            if (response.isCommitted()
                    || response.getStatusCode() == HttpStatus.SWITCHING_PROTOCOLS) {
                return;
            }
            String origin = exchange.getRequest().getHeaders().getOrigin();
            HttpHeaders headers = response.getHeaders();

            if (origin != null && !origin.isEmpty()) {
                headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
                headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            }
            dedup(headers, HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS);
            dedup(headers, HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS);
            dedup(headers, HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
            dedup(headers, HttpHeaders.ACCESS_CONTROL_MAX_AGE);
        }));
    }

    private static void dedup(HttpHeaders headers, String name) {
        List<String> values = headers.get(name);
        if (values != null && values.size() > 1) {
            headers.set(name, values.get(0));
        }
    }

    @Bean
    @Order(-2)
    public ErrorWebExceptionHandler corsErrorWebExceptionHandler() {
        return (exchange, ex) -> {
            addCorsHeaders(exchange);
            ServerHttpResponse response = exchange.getResponse();
            if (!response.isCommitted()) {
                HttpStatus status = resolveStatus(ex);
                response.setStatusCode(status);
                response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                String body = "{\"error\":\"" + status.getReasonPhrase() + "\"}";
                org.springframework.core.io.buffer.DataBuffer buf =
                        response.bufferFactory().wrap(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return response.writeWith(Mono.just(buf));
            }
            return Mono.empty();
        };
    }

    private static HttpStatus resolveStatus(Throwable ex) {

        String className = ex.getClass().getName();
        String message = ex.getMessage();
        boolean isTimeout = ex instanceof java.util.concurrent.TimeoutException
                || className.contains("TimeoutException")
                || (message != null && message.toLowerCase().contains("timeout"));
        return isTimeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
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
        headers.remove(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
        if (origin != null && !origin.isEmpty()) {
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        } else {
            headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        }
        headers.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "*");
        headers.set(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
    }
}