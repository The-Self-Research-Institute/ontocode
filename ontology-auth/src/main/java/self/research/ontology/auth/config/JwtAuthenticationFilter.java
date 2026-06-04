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
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

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

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    @Value("${ontocode.desktop.user.email:local@ontocode.desktop}")
    private String desktopUserEmail;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Desktop: no sign-in — always use the seeded local principal. Electron never
        // sends Authorization; do not parse web JWTs here (web UI must not share a
        // desktop-mode auth instance on the same port).
        if (desktopMode) {
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                var auth = new UsernamePasswordAuthenticationToken(
                    desktopUserEmail, null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        log.info("[JWT Filter] Processing: {} {} | Auth header present: {}",
                request.getMethod(), request.getRequestURI(), authHeader != null);

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            log.debug("[JWT Filter] Token (first 20 chars): {}...", token.substring(0, Math.min(20, token.length())));

            try {
                byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
                Key key = Keys.hmacShaKeyFor(keyBytes);

                Claims claims = Jwts.parser()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getPayload();

                // Use email as the principal name so getCurrentUserEmail() → authentication.getName()
                // returns the email address that userRepository.findByEmail() expects.
                // JWT sub = display username; email claim = email for userRepository.findByEmail()
                Object emailObj = claims.get("email");
                log.info("[JWT Filter] email claim raw: {} (type: {})", emailObj, emailObj != null ? emailObj.getClass().getSimpleName() : "null");
                String emailClaim = emailObj instanceof String ? (String) emailObj : null;
                String username = (emailClaim != null && !emailClaim.isBlank()) ? emailClaim : claims.getSubject();

                log.info("[JWT Filter] ✓ Extracted username: {}", username);

                if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    Object rolesClaim = claims.get("roles");
                    if (rolesClaim instanceof List<?> roleList) {
                        roleList.stream()
                                .filter(r -> r instanceof String)
                                .map(r -> new SimpleGrantedAuthority((String) r))
                                .forEach(authorities::add);
                    }
                    UserDetails userDetails = User.builder()
                        .username(username)
                        .password("")
                        .authorities(authorities)
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
