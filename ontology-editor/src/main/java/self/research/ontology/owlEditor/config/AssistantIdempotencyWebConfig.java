package self.research.ontology.owlEditor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AssistantIdempotencyWebConfig implements WebMvcConfigurer {

    private final AssistantIdempotencyInterceptor idempotencyInterceptor;

    public AssistantIdempotencyWebConfig(AssistantIdempotencyInterceptor idempotencyInterceptor) {
        this.idempotencyInterceptor = idempotencyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(idempotencyInterceptor)
                .addPathPatterns("/api/v1/code-assistant/sessions", "/api/v1/code-assistant/sessions/**")
                .order(Ordered.LOWEST_PRECEDENCE);
    }
}
