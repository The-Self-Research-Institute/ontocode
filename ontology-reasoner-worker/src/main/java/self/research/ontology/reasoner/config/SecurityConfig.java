package self.research.ontology.reasoner.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<Filter> internalTokenFilter(
            @Value("${ontocode.internal.token}") String expectedToken) {
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>();
        bean.setOrder(1);
        bean.addUrlPatterns("/api/*");
        bean.setFilter((request, response, chain) -> {
            HttpServletRequest req = (HttpServletRequest) request;
            if (req.getRequestURI().contains("/actuator/health")) {
                chain.doFilter(request, response);
                return;
            }
            String token = req.getHeader("X-Ontocode-Internal-Token");
            if (expectedToken != null && expectedToken.equals(token)) {
                chain.doFilter(request, response);
                return;
            }
            HttpServletResponse res = (HttpServletResponse) response;
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"success\":false,\"error\":\"Unauthorized\"}");
        });
        return bean;
    }
}
