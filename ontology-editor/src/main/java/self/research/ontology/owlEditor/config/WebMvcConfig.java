package self.research.ontology.owlEditor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UrlPathHelper;

/**
 * Web MVC Configuration for path matching and request interceptors.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final PerformanceLoggingInterceptor performanceLoggingInterceptor;
    private final FreeViewOnlyInterceptor freeViewOnlyInterceptor;
    private final EditorApiAuthInterceptor editorApiAuthInterceptor;
    private final SparqlQueryContextInterceptor sparqlQueryContextInterceptor;

    public WebMvcConfig(PerformanceLoggingInterceptor performanceLoggingInterceptor,
                        FreeViewOnlyInterceptor freeViewOnlyInterceptor,
                        EditorApiAuthInterceptor editorApiAuthInterceptor,
                        SparqlQueryContextInterceptor sparqlQueryContextInterceptor) {
        this.performanceLoggingInterceptor = performanceLoggingInterceptor;
        this.freeViewOnlyInterceptor = freeViewOnlyInterceptor;
        this.editorApiAuthInterceptor = editorApiAuthInterceptor;
        this.sparqlQueryContextInterceptor = sparqlQueryContextInterceptor;
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        UrlPathHelper urlPathHelper = new UrlPathHelper();
        urlPathHelper.setUrlDecode(false);
        configurer.setUrlPathHelper(urlPathHelper);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(editorApiAuthInterceptor)
                .addPathPatterns("/api/**")
                .order(0);
        registry.addInterceptor(sparqlQueryContextInterceptor)
                .addPathPatterns("/api/ontology/**")
                .order(1);
        registry.addInterceptor(performanceLoggingInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(freeViewOnlyInterceptor)
                .addPathPatterns("/api/**");
    }
}
