package self.research.ontocode.gateway.config;

import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Netty HTTP Server Configuration for Spring Cloud Gateway.
 * 
 * Configures Reactor Netty to allow encoded slashes (%2F) in URLs,
 * which is critical for hierarchical project IDs like "proj-123/file-456"
 * that are URL-encoded as "proj-123%2Ffile-456".
 * 
 * By default, Netty blocks %2F for security reasons. We explicitly allow it
 * for our hierarchical project ID use case by customizing the HTTP server.
 */
@Configuration
public class NettyConfig {

    @Bean
    public WebServerFactoryCustomizer<NettyReactiveWebServerFactory> nettyCustomizer() {
        return factory -> {
            // Allow encoded slashes by customizing the URI validation
            factory.addServerCustomizers(httpServer -> 
                httpServer.httpRequestDecoder(spec -> {
                    // Increase limits to allow larger URLs with encoded characters
                    spec.maxInitialLineLength(16384);  // Increased from default 4096
                    spec.maxHeaderSize(32768);  // Increased from default 8192
                    spec.maxChunkSize(16384);  // Increased from default 8192
                    spec.validateHeaders(false);  // Disable strict header validation
                    return spec;
                })
            );
        };
    }
}
