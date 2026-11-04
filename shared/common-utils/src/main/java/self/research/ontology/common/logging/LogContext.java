package self.research.ontology.common.logging;

import java.util.regex.Pattern;

public final class LogContext {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private LogContext() {
    }

    public static String userLabel(String userId) {
        return userId != null && SAFE_ID.matcher(userId).matches() ? "user=" + userId : "(anon)";
    }
}
