package self.research.ontology.owlEditor.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import self.research.ontology.owlEditor.service.AccessDenial;
import self.research.ontology.owlEditor.service.AccessDeniedApiException;

@Component
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String USER_ID_PARAMETER = "userId";

    private final boolean desktopMode;

    public CallerArgumentResolver(@Value("${ontocode.desktop.mode:false}") boolean desktopMode) {
        this.desktopMode = desktopMode;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CallerIdentity.class.equals(parameter.getParameterType())
                || (String.class.equals(parameter.getParameterType())
                && (parameter.hasParameterAnnotation(CallerEmail.class) || parameter.hasParameterAnnotation(CallerUserId.class)));
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        CallerIdentity caller = CallerIdentity.of(webRequest.getNativeRequest(HttpServletRequest.class));
        if (CallerIdentity.class.equals(parameter.getParameterType())) {
            return signedInOrDesktop(caller) ? caller : refuse();
        }
        boolean wantsUserId = parameter.hasParameterAnnotation(CallerUserId.class);
        String verified = wantsUserId ? userIdOrEmail(caller) : blankToNull(caller.email());
        if (verified != null) {
            return verified;
        }
        if (!desktopMode) {
            return refuse();
        }
        return webRequest.getParameter(wantsUserId ? USER_ID_PARAMETER : String.valueOf(parameter.getParameterName()));
    }

    private boolean signedInOrDesktop(CallerIdentity caller) {
        return desktopMode || caller.signedIn();
    }

    private static String userIdOrEmail(CallerIdentity caller) {
        return blankToNull(caller.userId()) != null ? caller.userId() : blankToNull(caller.email());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static <T> T refuse() {
        throw new AccessDeniedApiException(AccessDenial.unauthenticated());
    }
}
