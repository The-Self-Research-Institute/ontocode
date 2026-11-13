package self.research.ontology.migrator.core;

import java.util.regex.Pattern;

public final class NulSanitizer {

    private static final Pattern JSON_NUL_ESCAPE = Pattern.compile("(?<!\\\\)((?:\\\\\\\\)*)\\\\u0000");

    private NulSanitizer() {
    }

    public static String text(String value) {
        if (value == null || value.indexOf('\u0000') < 0) {
            return value;
        }
        return value.replace("\u0000", "");
    }

    public static String json(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = text(value);
        if (cleaned.indexOf("\\u0000") < 0) {
            return cleaned;
        }
        return JSON_NUL_ESCAPE.matcher(cleaned).replaceAll("$1");
    }
}
