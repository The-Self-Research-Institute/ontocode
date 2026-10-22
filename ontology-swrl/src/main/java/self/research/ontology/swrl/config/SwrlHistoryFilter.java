package self.research.ontology.swrl.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import self.research.ontology.swrl.service.SwrlHistoryReporter;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(2)
public class SwrlHistoryFilter extends OncePerRequestFilter {

    private static final Pattern RULE_WRITE = Pattern.compile("^/api/swrl/([^/]+)/rules(?:/(?!.*/(?:test|export)$).*)?$");
    private static final Set<String> WRITES = Set.of("POST", "PUT", "DELETE");

    private final SwrlHistoryReporter reporter;

    public SwrlHistoryFilter(SwrlHistoryReporter reporter) {
        this.reporter = reporter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Matcher match = RULE_WRITE.matcher(request.getRequestURI());
        if (!WRITES.contains(request.getMethod()) || !match.matches()) {
            chain.doFilter(request, response);
            return;
        }
        reporter.aroundRequest(match.group(1), request.getHeader("Authorization"), () -> {
            chain.doFilter(request, response);
            return response.getStatus() < 300;
        });
    }
}
