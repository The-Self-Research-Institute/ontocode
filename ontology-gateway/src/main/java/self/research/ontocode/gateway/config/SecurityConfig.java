package self.research.ontocode.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                // CORS is handled entirely by corsEarlySetFilter (WebFilter at HIGHEST_PRECEDENCE),
                // which runs before Spring Security and handles preflight directly.
                .cors(cors -> cors.disable())

                .csrf(csrf -> csrf.disable())

                .exceptionHandling(exceptionHandling ->
                        exceptionHandling.authenticationEntryPoint(
                                new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)
                        )
                )

                .authorizeExchange(exchanges -> exchanges
                        .anyExchange().permitAll()
                )

                .httpBasic(httpBasic -> httpBasic.disable())

                .formLogin(formLogin -> formLogin.disable())

                .logout(logout -> logout.disable());

        return http.build();
    }
}