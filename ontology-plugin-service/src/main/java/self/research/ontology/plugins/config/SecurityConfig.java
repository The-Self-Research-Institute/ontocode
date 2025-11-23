package self.research.ontology.plugins.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configure(http))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints - browsing, downloading, and stats
                .requestMatchers(HttpMethod.GET, "/api/plugins/**").permitAll()

                // Public endpoints - rating (for development - add auth in production)
                .requestMatchers(HttpMethod.POST, "/api/plugins/*/rate").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/plugins/*/helpful").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/api/plugins/ratings/*").permitAll()
                
                // Public endpoints - installation tracking (for development)
                .requestMatchers(HttpMethod.POST, "/api/plugins/*/install").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/plugins/*/uninstall").permitAll()

                // Protected endpoints - publishing requires authentication
                .requestMatchers(HttpMethod.POST, "/api/plugins").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/plugins/**").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/plugins/**").authenticated()

                // Health check
                .requestMatchers("/actuator/health").permitAll()

                // Default - allow all for development (CHANGE IN PRODUCTION)
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
