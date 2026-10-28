package self.research.ontology.swrl.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import self.research.ontology.swrl.service.OwnerContext;

import java.io.IOException;

@Component
public class OwnerContextFilter extends OncePerRequestFilter {

    private static final String USER_HEADER = "X-Ontocode-User-Id";
    private static final String PREFIX = "/api/swrl/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        OwnerContext.set(ownerOf(request.getHeader(USER_HEADER), request.getRequestURI()));
        try {
            chain.doFilter(request, response);
        } finally {
            OwnerContext.clear();
        }
    }

    public static String ownerOf(String userHeader, String path) {
        if (userHeader != null && !userHeader.isBlank()) {
            return "user:" + userHeader.trim();
        }
        if (path != null && path.startsWith(PREFIX)) {
            String rest = path.substring(PREFIX.length());
            int slash = rest.indexOf('/');
            String project = slash < 0 ? rest : rest.substring(0, slash);
            if (!project.isBlank() && !project.equals("infer") && !project.equals("internal")) {
                return "project:" + project;
            }
        }
        return null;
    }
}
