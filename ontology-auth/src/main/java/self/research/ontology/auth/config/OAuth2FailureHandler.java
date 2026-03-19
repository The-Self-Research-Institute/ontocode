package self.research.ontology.auth.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import self.research.ontology.auth.controller.OidcController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Custom OAuth2 failure handler that forwards errors back to the VS Code extension
 * callback URI when one was stored in the session (e.g. for already_logged_in errors).
 */
public class OAuth2FailureHandler extends SimpleUrlAuthenticationFailureHandler {

    public OAuth2FailureHandler(String defaultFailureUrl) {
        super(defaultFailureUrl);
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {

        HttpSession session = request.getSession(false);
        String redirectUri = null;

        if (session != null) {
            Object val = session.getAttribute(OidcController.SESSION_REDIRECT_URI);
            if (val instanceof String s && !s.isBlank()) {
                redirectUri = s;
            }
            session.removeAttribute(OidcController.SESSION_REDIRECT_URI);
            session.removeAttribute(OidcController.SESSION_EMBEDDED_VIEW);
        }

        // Spring passes the OAuth2 error code (e.g. "already_logged_in") as a request parameter
        String oauth2Error = request.getParameter("error");
        String errorMessage = oauth2Error != null && !oauth2Error.isBlank()
                ? oauth2Error
                : (exception != null ? exception.getMessage() : "Authentication failed");

        if (redirectUri != null) {
            String separator = redirectUri.contains("?") ? "&" : "?";
            String redirectUrl = redirectUri + separator
                    + "error=" + URLEncoder.encode(errorMessage, StandardCharsets.UTF_8);
            getRedirectStrategy().sendRedirect(request, response, redirectUrl);
            return;
        }

        super.onAuthenticationFailure(request, response, exception);
    }
}
