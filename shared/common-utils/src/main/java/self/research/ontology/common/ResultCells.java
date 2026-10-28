package self.research.ontology.common;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ResultCells {

    public static final int MAX_FILTER_LENGTH = 200;

    private static final Pattern NUMBER = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");

    private ResultCells() {
    }

    public static String normalizeFilter(String filter) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        return needle.length() > MAX_FILTER_LENGTH ? needle.substring(0, MAX_FILTER_LENGTH) : needle;
    }

    public static boolean containsIgnoreCase(String text, int from, String lowerNeedle) {
        int last = text.length() - lowerNeedle.length();
        for (int i = from; i <= last; i++) {
            if (text.regionMatches(true, i, lowerNeedle, 0, lowerNeedle.length())) {
                return true;
            }
        }
        return false;
    }

    public static Double number(String text) {
        String t = text.trim();
        if (!NUMBER.matcher(t).matches()) {
            return null;
        }
        try {
            double d = Double.parseDouble(t);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static int compare(Double numberA, String lowerA, Double numberB, String lowerB) {
        if (numberA != null && numberB != null) {
            return Double.compare(numberA, numberB);
        }
        if (numberA != null) {
            return -1;
        }
        if (numberB != null) {
            return 1;
        }
        return lowerA.compareTo(lowerB);
    }

    public static int compareCells(String a, String b) {
        return compare(number(a), a.toLowerCase(Locale.ROOT), number(b), b.toLowerCase(Locale.ROOT));
    }
}
