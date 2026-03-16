package self.research.ontology.auth.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.Key;
import java.util.ArrayList;

/**
 * JWT Authentication Filter for validating Bearer tokens
 * Extracts and validates JWT tokens from Authorization header
 * Sets authentication context for authenticated requests
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        
        log.info("[JWT Filter] Processing: {} {} | Auth header present: {}", 
                request.getMethod(), request.getRequestURI(), authHeader != null);

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            log.debug("[JWT Filter] Token (first 20 chars): {}...", token.substring(0, Math.min(20, token.length())));

            try {
                // Use Base64 decoding to match JwtUtil
                byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
                Key key = Keys.hmacShaKeyFor(keyBytes);

                Claims claims = Jwts.parser()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getPayload();

                String username = claims.getSubject();
                
                log.info("[JWT Filter] ✓ Extracted username: {}", username);

                if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    UserDetails userDetails = User.builder()
                        .username(username)
                        .password("")
                        .authorities(new ArrayList<>())
                        .build();

                    UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.info("[JWT Filter] ✓ Authentication set for user: {}", username);
                } else if (SecurityContextHolder.getContext().getAuthentication() != null) {
                    log.debug("[JWT Filter] Authentication already exists in context");
                }
            } catch (Exception e) {
                log.error("[JWT Filter] ✗ JWT validation failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
                log.debug("[JWT Filter] Full stack trace:", e);
            }
        } else {
            log.warn("[JWT Filter] ✗ No valid Authorization header found for {} {}", 
                    request.getMethod(), request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }
}
