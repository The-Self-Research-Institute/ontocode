package self.research.ontocode.gateway.config;

import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Mono;

@Configuration
public class GatewayCorsConfig {

    /**
     * Strips duplicate CORS headers that backend services may add,
     * so only the gateway's global CORS configuration (from properties) is used.
     * Runs with highest priority to intercept response headers before they are sent.
     */
    @Bean
    public GlobalFilter corsHeaderCleanupFilter() {
        return new GlobalFilter() {
            @Override
            public Mono<Void> filter(org.springframework.web.server.ServerWebExchange exchange,
                                     org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
                ServerHttpResponse originalResponse = exchange.getResponse();
                ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
                    @Override
                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                        // Strip backend CORS headers before gateway adds its own
                        HttpHeaders headers = getDelegate().getHeaders();
                        stripDuplicateCorsHeaders(headers);
                        return super.writeWith(body);
                    }

                    @Override
                    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                        HttpHeaders headers = getDelegate().getHeaders();
                        stripDuplicateCorsHeaders(headers);
                        return super.writeAndFlushWith(body);
                    }
                };
                return chain.filter(exchange.mutate().response(decoratedResponse).build());
            }
        };
    }

    /**
     * If there are multiple Access-Control-Allow-Origin headers (one from the
     * backend, one from the gateway global CORS), keep only the first value.
     */
    private static void stripDuplicateCorsHeaders(HttpHeaders headers) {
        dedup(headers, HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        dedup(headers, HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
        dedup(headers, HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS);
        dedup(headers, HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS);
        dedup(headers, HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
        dedup(headers, HttpHeaders.ACCESS_CONTROL_MAX_AGE);
    }

    private static void dedup(HttpHeaders headers, String name) {
        java.util.List<String> values = headers.get(name);
        if (values != null && values.size() > 1) {
            String first = values.get(0);
            headers.set(name, first);
        }
    }
}