package self.research.ontology.reasoner.config;

import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<Filter> internalTokenFilter(
            @Value("${ontocode.internal.token}") String expectedToken) {
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>();
        bean.setOrder(1);
        bean.addUrlPatterns("/api/*");
        bean.setFilter(new InternalTokenFilter(expectedToken));
        return bean;
    }
}
