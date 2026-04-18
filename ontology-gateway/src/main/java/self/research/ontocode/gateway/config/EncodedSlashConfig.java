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

/**
 * Configuration to preserve encoded slashes in URLs when routing to backend services.
 * This is critical for hierarchical project IDs like "proj-123/file-456" which
 * are URL-encoded as "proj-123%2Ffile-456".
 * 
 * Spring Cloud Gateway by default decodes paths before routing, which breaks
 * hierarchical project ID handling. This filter preserves the original encoded path.
 */
@Configuration
public class EncodedSlashConfig {

    /**
     * Global filter that preserves encoded characters in the path when routing.
     * Reconstructs the request with the raw (encoded) path to prevent Spring from
     * decoding slashes before forwarding to backend services.
     */
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
            
            // Skip processing if path doesn't contain encoded characters
            if (originalPath == null || !originalPath.contains("%")) {
                return chain.filter(exchange);
            }
            
            // Reconstruct URI with raw (encoded) path to preserve %2F and other encoded chars
            URI newUri;
            try {
                UriComponentsBuilder builder = UriComponentsBuilder.fromUri(request.getURI());
                // Use fromUriString with raw path to preserve encoding
                String uriString = request.getURI().getScheme() + "://" + 
                                  request.getURI().getAuthority() + 
                                  originalPath;
                if (originalQuery != null) {
                    uriString += "?" + originalQuery;
                }
                newUri = URI.create(uriString);
            } catch (Exception e) {
                // If reconstruction fails, use original URI
                return chain.filter(exchange);
            }
            
            // Create modified request with preserved encoding
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
            // Run with highest priority, before routing
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
