package self.research.ontology.auth.config; // Adjust package as per your project

import self.research.ontology.auth.security.RateLimitingFilter;
import self.research.ontology.auth.security.SecurityValidationFilter;
import self.research.ontology.auth.service.CustomUserDetailsService; // Adjust package
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration; // Import this
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final CustomUserDetailsService customUserDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityValidationFilter securityValidationFilter;
    private final RateLimitingFilter rateLimitingFilter;

    public SecurityConfig(CustomUserDetailsService customUserDetailsService, 
                         JwtAuthenticationFilter jwtAuthenticationFilter,
                         SecurityValidationFilter securityValidationFilter,
                         RateLimitingFilter rateLimitingFilter) {
        this.customUserDetailsService = customUserDetailsService;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.securityValidationFilter = securityValidationFilter;
        this.rateLimitingFilter = rateLimitingFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(customUserDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // Disable CSRF for stateless API
                .cors(AbstractHttpConfigurer::disable) // Disable CORS - handled by gateway
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // Always allow preflight
                        .requestMatchers("/api/auth/**").permitAll() // Allow public access to auth endpoints
                        .requestMatchers("/api/invitations/details/**").permitAll() // Allow public access to view invitation details
                        .requestMatchers("/api/invitations/request-resend/**").permitAll() // Allow public access to request invitation resend
                        .requestMatchers("/invite").permitAll() // Allow public access to web invitation redirect page
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/**").permitAll() // Allow actuator endpoints for health checks
                        .anyRequest().authenticated() // All other requests require authentication
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // Stateless sessions
                // Add security filters in order: Rate Limiting -> Security Validation -> JWT Authentication
                .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(securityValidationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * CORS Configuration
     * Allows cross-origin requests from frontend applications
     */
    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Allow requests from common development origins and production
        configuration.setAllowedOriginPatterns(Arrays.asList(
            "http://localhost:*",              // Local development (any port)
            "http://127.0.0.1:*",             // Local development (loopback)
            "https://localhost:*",            // Local development over HTTPS
            "http://ec2-13-218-153-101.compute-1.amazonaws.com:*",
            "https://ec2-13-218-153-101.compute-1.amazonaws.com:*",
            "vscode-webview://*",             // VS Code webview
            "vscode-webview-resource://*",
            "https://*.vscode-cdn.net",       // VS Code CDN
            "https://*.vscode-unpkg.net",     // VS Code unpkg CDN
            "*"                               // Allow all origins (for development)
        ));
        
        // Allow all HTTP methods
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        
        // Allow all headers
        configuration.setAllowedHeaders(Arrays.asList("*"));
        
        // Allow credentials (cookies, authorization headers)
        configuration.setAllowCredentials(false); // Changed to false for wildcard origin
        
        // Cache preflight response for 1 hour
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        
        return source;
    }

}
