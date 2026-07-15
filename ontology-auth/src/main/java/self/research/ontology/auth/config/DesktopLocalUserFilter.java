package self.research.ontology.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Desktop-only authentication filter.
 *
 * The desktop build runs entirely on localhost with no real accounts, so the
 * security chain is permit-all (see {@code SecurityConfig} when
 * {@code ontocode.desktop.mode=true}). This filter still installs a fixed
 * {@code Authentication} for the seeded local user so controllers that resolve
 * the current principal via {@code SecurityContextHolder.getName()} (for
 * example {@code ProjectController} and {@code WorkspaceController}) keep
 * working without a JWT.
 *
 * The principal email is supplied from the verified license (passed by the
 * Electron shell as {@code ontocode.desktop.user.email}); it falls back to a
 * generic local identity when no license is imported (FREE tier).
 */
public class DesktopLocalUserFilter extends OncePerRequestFilter {

    private final String localEmail;

    public DesktopLocalUserFilter(String localEmail) {
        this.localEmail = localEmail;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    localEmail, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }
}
