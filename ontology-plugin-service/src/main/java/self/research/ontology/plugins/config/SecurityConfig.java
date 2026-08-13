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

            @Qualifier("pluginJwtAuthFilter") JwtAuthenticationFilter jwtAuthenticationFilter,
            CorsConfigurationSource corsConfigurationSource) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean("pluginSecurityFilterChain")
    public SecurityFilterChain pluginSecurityFilterChain(HttpSecurity http) throws Exception {
        http

            .securityMatcher("/api/plugins/**", "/api/reasoner/**")
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth

                .requestMatchers(HttpMethod.GET, "/api/plugins/**").permitAll()

                .requestMatchers(HttpMethod.POST, "/api/plugins").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/plugins/**").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/plugins/**").authenticated()

                .requestMatchers("/actuator/health").permitAll()

                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
