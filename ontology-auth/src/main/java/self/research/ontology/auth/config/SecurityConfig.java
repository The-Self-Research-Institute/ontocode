package self.research.ontology.auth.config; // Adjust package as per your project

import self.research.ontology.auth.security.RateLimitingFilter;
import self.research.ontology.auth.security.SecurityValidationFilter;
import self.research.ontology.auth.service.CustomUserDetailsService; // Adjust package
import self.research.ontology.auth.service.OidcUserService;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final CustomUserDetailsService customUserDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityValidationFilter securityValidationFilter;
    private final RateLimitingFilter rateLimitingFilter;
    
    @Autowired(required = false)
    private OidcUserService oidcUserService;
    
    @Autowired(required = false)
    private OidcConfig oidcConfig;

    @Autowired(required = false)
    private ClientRegistrationRepository clientRegistrationRepository;

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
        final boolean oidcEnabled = oidcConfig != null
            && oidcConfig.isEnabled()
            && !oidcConfig.getEnabledProviders().isEmpty();

        http
                .csrf(AbstractHttpConfigurer::disable) // Disable CSRF for stateless API
                .cors(AbstractHttpConfigurer::disable) // Disable CORS - handled by gateway
                // Disable X-Frame-Options so the VS Code webview iframe can load auth service
                // responses (authorize endpoint, success page, etc.).
                // Keycloak is separately configured via --spi-security-headers-default-x-frame-options.
                .headers(headers -> headers.frameOptions(fo -> fo.disable()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // Always allow preflight
                        .requestMatchers("/api/auth/**").permitAll() // Allow public access to auth endpoints
                        .requestMatchers("/api/invitations/details/**").permitAll() // Allow public access to view invitation details
                        .requestMatchers("/api/invitations/request-resend/**").permitAll() // Allow public access to request invitation resend
                        .requestMatchers("/invite").permitAll() // Allow public access to web invitation redirect page
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/login/oauth2/**").permitAll() // Allow OAuth2 login endpoints
                        .requestMatchers("/oauth2/**").permitAll() // Allow OAuth2 callback endpoints
                        .requestMatchers("/actuator/**").permitAll() // Allow actuator endpoints for health checks
                        .anyRequest().authenticated() // All other requests require authentication
                )
                    // OAuth2 flow needs HttpSession for state/authorization request tracking.
                    .sessionManagement(session -> session.sessionCreationPolicy(
                        oidcEnabled ? SessionCreationPolicy.IF_REQUIRED : SessionCreationPolicy.STATELESS
                    ))
                // Add security filters in order: Rate Limiting -> Security Validation -> JWT Authentication
                .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(securityValidationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        
        // Configure OAuth2 login if OIDC is enabled
                if (oidcEnabled) {
            http.oauth2Login(oauth2 -> oauth2
                    .authorizationEndpoint(endpoint -> {
                        if (clientRegistrationRepository != null) {
                            endpoint.authorizationRequestResolver(oidcAuthorizationRequestResolver(clientRegistrationRepository));
                        }
                    })
                    .userInfoEndpoint(userInfo -> {
                        if (oidcUserService != null) {
                            userInfo.userService(oidcUserService);
                        }
                    })
                    .successHandler(new OAuth2SuccessHandler("/api/auth/oidc/success"))
                    .failureHandler(new OAuth2FailureHandler("/api/auth/oidc/failure"))
            );
        }

        return http.build();
    }

    private OAuth2AuthorizationRequestResolver oidcAuthorizationRequestResolver(
            ClientRegistrationRepository registrations) {
        final DefaultOAuth2AuthorizationRequestResolver delegate =
                new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");

        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                OAuth2AuthorizationRequest req = delegate.resolve(request);
                return customizeAuthorizationRequest(req, request);
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
                OAuth2AuthorizationRequest req = delegate.resolve(request, clientRegistrationId);
                return customizeAuthorizationRequest(req, request);
            }
        };
    }

    private OAuth2AuthorizationRequest customizeAuthorizationRequest(
            OAuth2AuthorizationRequest req,
            HttpServletRequest request) {
        if (req == null) {
            return null;
        }

        String kcAction = request.getParameter("kc_action");
        if (kcAction == null || kcAction.isBlank()) {
            return req;
        }

        Map<String, Object> additional = new HashMap<>(req.getAdditionalParameters());
        if ("register".equals(kcAction)) {
            // OIDC standard: prompt=create opens the registration form directly.
            // Works with Keycloak 26+ and avoids the "already_logged_in" issue that
            // the /registrations URI workaround caused.
            additional.put("prompt", "create");
        } else {
            additional.put("kc_action", kcAction);
        }
        return OAuth2AuthorizationRequest.from(req)
                .additionalParameters(additional)
                .build();
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
            "http://13.218.153.101:*", // Production API URL
            "https://ontocode.selfresearch.org:*",
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
