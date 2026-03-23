package self.research.ontology.owlEditor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UrlPathHelper;

/**
 * Web MVC Configuration for path matching
 * 
 * Enables support for hierarchical project IDs with slashes (e.g., "project-123/file-456")
 * by disabling the default URL decoding behavior that would truncate at the first slash.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        UrlPathHelper urlPathHelper = new UrlPathHelper();
        urlPathHelper.setUrlDecode(false); // Don't decode URL-encoded slashes before matching
        configurer.setUrlPathHelper(urlPathHelper);
    }
}
