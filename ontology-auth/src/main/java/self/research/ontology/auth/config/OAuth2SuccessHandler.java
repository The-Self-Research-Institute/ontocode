package self.research.ontology.auth.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import self.research.ontology.auth.controller.OidcController;

import java.io.IOException;

/**
 * Custom OAuth2 success handler that preserves query parameters
 * from the original auth request (like embedded_view and redirect_uri)
 */
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {
    
    public OAuth2SuccessHandler(String defaultTargetUrl) {
        super(defaultTargetUrl);
    }
    
    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        
        // Get the original request parameters
        String embeddedView = request.getParameter("embedded_view");
        String redirectUri = request.getParameter("redirect_uri");

        // OAuth2 callback only contains code/state, so preserve custom values via session
        // and cookie fallback (cookie survives session-fixation ID rotation).
        HttpSession session = request.getSession(false);
        if ((embeddedView == null || embeddedView.isBlank()) && session != null) {
            Object val = session.getAttribute(OidcController.SESSION_EMBEDDED_VIEW);
            if (val instanceof String s && !s.isBlank()) {
                embeddedView = s;
            }
        }
        if ((redirectUri == null || redirectUri.isBlank()) && session != null) {
            Object val = session.getAttribute(OidcController.SESSION_REDIRECT_URI);
            if (val instanceof String s && !s.isBlank()) {
                redirectUri = s;
            }
        }
        // Cookie fallback — used when the session was rotated by session-fixation protection.
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (jakarta.servlet.http.Cookie c : cookies) {
                if ("OIDC_REDIRECT_URI".equals(c.getName())
                        && c.getValue() != null && !c.getValue().isBlank()
                        && (redirectUri == null || redirectUri.isBlank())) {
                    try {
                        redirectUri = java.net.URLDecoder.decode(
                                c.getValue(), java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception ex) {
                        redirectUri = c.getValue();
                    }
                    // Immediately expire the cookie
                    jakarta.servlet.http.Cookie clear =
                            new jakarta.servlet.http.Cookie("OIDC_REDIRECT_URI", "");
                    clear.setPath("/");
                    clear.setMaxAge(0);
                    response.addCookie(clear);
                }
                if ("OIDC_EMBEDDED_VIEW".equals(c.getName())
                        && c.getValue() != null && !c.getValue().isBlank()
                        && (embeddedView == null || embeddedView.isBlank())) {
                    embeddedView = c.getValue();
                    // Immediately expire the cookie
                    jakarta.servlet.http.Cookie clear =
                            new jakarta.servlet.http.Cookie("OIDC_EMBEDDED_VIEW", "");
                    clear.setPath("/");
                    clear.setMaxAge(0);
                    response.addCookie(clear);
                }
            }
        }
        
        // Build the target URL with preserved parameters.
        // IMPORTANT: always start from the fixed success endpoint — never from
        // getDefaultTargetUrl(), because this handler is a singleton and
        // setDefaultTargetUrl() would permanently corrupt the shared state,
        // causing every subsequent login to compound extra params onto the URL.
        StringBuilder targetUrl = new StringBuilder("/api/auth/oidc/success");
        boolean hasParams = false;

        if (embeddedView != null && !embeddedView.isBlank()) {
            targetUrl.append("?embedded_view=").append(embeddedView);
            hasParams = true;
        }

        if (redirectUri != null && !redirectUri.isBlank()) {
            targetUrl.append(hasParams ? "&" : "?");
            targetUrl.append("redirect_uri=").append(
                    java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8));
        }

        if (session != null) {
            session.removeAttribute(OidcController.SESSION_EMBEDDED_VIEW);
            session.removeAttribute(OidcController.SESSION_REDIRECT_URI);
        }

        // Clear Spring Security's saved-request cache and auth attributes,
        // then redirect directly — do NOT call super or setDefaultTargetUrl().
        clearAuthenticationAttributes(request);
        getRedirectStrategy().sendRedirect(request, response, targetUrl.toString());
    }
}
