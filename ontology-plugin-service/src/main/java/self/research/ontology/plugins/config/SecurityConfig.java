package self.research.ontology.plugins.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration("pluginSecurityConfig")
@EnableWebSecurity
@Order(1)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(
            // @Qualifier ensures we pick the plugin-specific filter in the merged
            // desktop context where auth's JwtAuthenticationFilter is also present.
            @Qualifier("pluginJwtAuthFilter") JwtAuthenticationFilter jwtAuthenticationFilter,
            CorsConfigurationSource corsConfigurationSource) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean("pluginSecurityFilterChain")
    public SecurityFilterChain pluginSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            // Restrict this chain to plugin and reasoner endpoints only.
            // In the merged desktop context auth's chain (@Order 1) handles all other requests.
            .securityMatcher("/api/plugins/**", "/api/reasoner/**")
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
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

                // Plugin publishing - allow without auth for development (SECURE IN PRODUCTION)
                .requestMatchers(HttpMethod.POST, "/api/plugins").permitAll()
                .requestMatchers(HttpMethod.PUT, "/api/plugins/**").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/api/plugins/**").permitAll()

                // NOTE: For production, change the above to:
                // .requestMatchers(HttpMethod.POST, "/api/plugins").authenticated()
                // .requestMatchers(HttpMethod.PUT, "/api/plugins/**").authenticated()
                // .requestMatchers(HttpMethod.DELETE, "/api/plugins/**").authenticated()

                // Health check
                .requestMatchers("/actuator/health").permitAll()

                // Default - allow all for development (CHANGE IN PRODUCTION)
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
