package self.research.ontology.owlEditor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UrlPathHelper;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final PerformanceLoggingInterceptor performanceLoggingInterceptor;
    private final FreeViewOnlyInterceptor freeViewOnlyInterceptor;
    private final EditorApiAuthInterceptor editorApiAuthInterceptor;
    private final SparqlQueryContextInterceptor sparqlQueryContextInterceptor;
    private final InternalApiAuthInterceptor internalApiAuthInterceptor;
    private final CallerArgumentResolver callerArgumentResolver;

    public WebMvcConfig(PerformanceLoggingInterceptor performanceLoggingInterceptor,
                        FreeViewOnlyInterceptor freeViewOnlyInterceptor,
                        EditorApiAuthInterceptor editorApiAuthInterceptor,
                        SparqlQueryContextInterceptor sparqlQueryContextInterceptor,
                        InternalApiAuthInterceptor internalApiAuthInterceptor,
                        CallerArgumentResolver callerArgumentResolver) {
        this.performanceLoggingInterceptor = performanceLoggingInterceptor;
        this.freeViewOnlyInterceptor = freeViewOnlyInterceptor;
        this.editorApiAuthInterceptor = editorApiAuthInterceptor;
        this.sparqlQueryContextInterceptor = sparqlQueryContextInterceptor;
        this.internalApiAuthInterceptor = internalApiAuthInterceptor;
        this.callerArgumentResolver = callerArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(callerArgumentResolver);
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        UrlPathHelper urlPathHelper = new UrlPathHelper();
        urlPathHelper.setUrlDecode(false);
        configurer.setUrlPathHelper(urlPathHelper);
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new StringToReasonerTypeConverter());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalApiAuthInterceptor)
                .addPathPatterns("/internal/**", "/api/import-worker/**")
                .order(-1);
        registry.addInterceptor(editorApiAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/import-worker/**")
                .order(0);
        registry.addInterceptor(sparqlQueryContextInterceptor)
                .addPathPatterns("/api/ontology/**", "/api/sparql/query/**")
                .order(1);
        registry.addInterceptor(performanceLoggingInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(freeViewOnlyInterceptor)
                .addPathPatterns("/api/**");
    }
}
