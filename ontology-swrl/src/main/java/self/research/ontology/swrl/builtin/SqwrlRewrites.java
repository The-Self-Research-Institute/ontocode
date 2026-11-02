package self.research.ontology.swrl.builtin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SqwrlRewrites {

    private static final Pattern NTH_LAST = Pattern.compile("sqwrl:nthLast\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern NTH_GREATEST = Pattern.compile("sqwrl:nthGreatest\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern NOT_NTH_LAST_ONE = Pattern.compile("sqwrl:notNthLast\\(\\s*1\\s*\\)");
    private static final Pattern MIN_MAX = Pattern.compile("^(.*->\\s*)sqwrl:(min|max)\\(\\s*(\\?\\w+)\\s*\\)\\s*$", Pattern.DOTALL);
    private static final String NOT_NUMERIC = "expecting numeric literal";

    private SqwrlRewrites() {
    }

    public static String sliceAliases(String query) {
        if (query == null || !query.contains("sqwrl:")) {
            return query;
        }
        String rewritten = query;
        Matcher last = NTH_LAST.matcher(rewritten);
        if (last.find()) {
            rewritten = flipOrder(last.replaceAll("sqwrl:nth($1)"));
        }
        Matcher greatest = NTH_GREATEST.matcher(rewritten);
        if (greatest.find()) {
            rewritten = descending(greatest.replaceAll("sqwrl:nth($1)"));
        }
        return NOT_NTH_LAST_ONE.matcher(rewritten).replaceAll("sqwrl:notNthLastSlice(1, 1)");
    }

    private static String flipOrder(String query) {
        return query.replace("sqwrl:orderByDescending(", "sqwrl:orderByTemporary(").replace("sqwrl:orderBy(", "sqwrl:orderByDescending(")
                .replace("sqwrl:orderByTemporary(", "sqwrl:orderBy(");
    }

    private static String descending(String query) {
        return query.replace("sqwrl:orderBy(", "sqwrl:orderByDescending(");
    }

    public static String minMaxByOrdering(String query, String failure) {
        if (query == null || failure == null || !failure.contains(NOT_NUMERIC)) {
            return null;
        }
        Matcher m = MIN_MAX.matcher(query.trim());
        if (!m.matches()) {
            return null;
        }
        String column = m.group(3);
        String order = m.group(2).equals("min") ? "orderBy" : "orderByDescending";
        return m.group(1) + "sqwrl:select(" + column + ") ^ sqwrl:" + order + "(" + column + ") ^ sqwrl:limit(1) ^ sqwrl:columnNames(\""
                + m.group(2) + "(" + column + ")\")";
    }
}
