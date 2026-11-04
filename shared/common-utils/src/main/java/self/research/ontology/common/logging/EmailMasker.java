package self.research.ontology.common.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmailMasker {

    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+'-])[A-Za-z0-9._%+'-]*@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,})");

    private EmailMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.indexOf('@') < 0) {
            return text;
        }
        Matcher matcher = EMAIL.matcher(text);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(match.group(1) + "***@" + match.group(2)));
    }
}
