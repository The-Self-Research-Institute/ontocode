package self.research.ontocode.gateway.config;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;

@Configuration
public class EncodedSlashConfig {

    @Bean
    public GlobalFilter preserveEncodedSlashFilter() {
        return new PreserveEncodedSlashFilter();
    }

    private static class PreserveEncodedSlashFilter implements GlobalFilter, Ordered {

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            ServerHttpRequest request = exchange.getRequest();
            String originalPath = request.getURI().getRawPath();
            String originalQuery = request.getURI().getRawQuery();

            if (originalPath == null || !originalPath.contains("%")) {
                return chain.filter(exchange);
            }

            URI newUri;
            try {
                UriComponentsBuilder builder = UriComponentsBuilder.fromUri(request.getURI());

                String uriString = request.getURI().getScheme() + "://" +
                                  request.getURI().getAuthority() +
                                  originalPath;
                if (originalQuery != null) {
                    uriString += "?" + originalQuery;
                }
                newUri = URI.create(uriString);
            } catch (Exception e) {

                return chain.filter(exchange);
            }

            ServerHttpRequest modifiedRequest = request.mutate()
                    .uri(newUri)
                    .build();

            ServerWebExchange modifiedExchange = exchange.mutate()
                    .request(modifiedRequest)
                    .build();

            return chain.filter(modifiedExchange);
        }

        @Override
        public int getOrder() {

            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
