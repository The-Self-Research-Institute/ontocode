package self.research.ontology.owlEditor.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tomcat Configuration to allow encoded slashes in URLs.
 * 
 * This is critical for hierarchical project IDs like "proj-123/file-456" which
 * are URL-encoded as "proj-123%2Ffile-456".
 * 
 * By default, Tomcat rejects URLs with encoded slashes (%2F) for security reasons.
 * This configuration explicitly allows them for our use case.
 */
@Configuration
public class TomcatConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCustomizer() {
        return factory -> {
            factory.addConnectorCustomizers((Connector connector) -> {
                // Allow encoded slashes in URLs (e.g., %2F)
                connector.setEncodedSolidusHandling("decode");
                
                // Relax URL validation to allow special characters
                connector.setProperty("relaxedPathChars", "|{}[]");
                connector.setProperty("relaxedQueryChars", "|{}[]");
            });
        };
    }
}
