package self.research.ontology.owlEditor.service.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class SearchMatcher {

    public static final int MAX_REGEX_LENGTH = 200;
    public static final int MAX_RANGES_PER_VALUE = 20;
    private static final long REGEX_BUDGET_NANOS = 200_000_000L;

    private final String raw;
    private final Pattern pattern;
    private final boolean regex;
    private final boolean caseSensitive;
    private final boolean wholeWords;
    private final String prefilter;

    private SearchMatcher(String raw, Pattern pattern, boolean regex, boolean caseSensitive, boolean wholeWords,
                          String prefilter) {
        this.raw = raw;
        this.pattern = pattern;
        this.regex = regex;
        this.caseSensitive = caseSensitive;
        this.wholeWords = wholeWords;
        this.prefilter = prefilter;
    }

    public static SearchMatcher plain(String query) {
        String trimmed = query == null ? "" : query.trim();
        return new SearchMatcher(trimmed, null, false, false, false, null);
    }

    public static SearchMatcher regex(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.length() > MAX_REGEX_LENGTH) {
            throw new IllegalArgumentException("Regular expression is longer than " + MAX_REGEX_LENGTH + " characters");
        }
        if (trimmed.isEmpty()) {
            return new SearchMatcher("", null, true, false, false, null);
        }
        try {
            Pattern compiled = Pattern.compile(trimmed, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            return new SearchMatcher(trimmed, compiled, true, false, false, trimmed);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regular expression: " + e.getDescription());
        }
    }

    public static SearchMatcher of(String query, boolean regex) {
        return regex ? regex(query) : plain(query);
    }

    public static SearchMatcher of(String query, boolean regex, boolean caseSensitive, boolean wholeWords,
                                   boolean ignoreWhitespace) {
        if (!regex && !wholeWords && !ignoreWhitespace) {
            String trimmed = query == null ? "" : query.trim();
            return new SearchMatcher(trimmed, null, false, caseSensitive, false, null);
        }
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.length() > MAX_REGEX_LENGTH) {
            throw new IllegalArgumentException("Search text is longer than " + MAX_REGEX_LENGTH + " characters");
        }
        if (trimmed.isEmpty()) {
            return new SearchMatcher("", null, regex, caseSensitive, false, null);
        }
        String body;
        String prefilter;
        if (regex) {
            body = trimmed;
            prefilter = trimmed;
        } else if (ignoreWhitespace) {
            StringBuilder quoted = new StringBuilder();
            StringBuilder xpath = new StringBuilder();
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (Character.isWhitespace(c)) {
                    continue;
                }
                if (quoted.length() > 0) {
                    quoted.append("\\s*");
                    xpath.append("\\s*");
                }
                quoted.append(Pattern.quote(String.valueOf(c)));
                xpath.append("\\|.-^?*+{}()[]$".indexOf(c) >= 0 ? "\\" + c : String.valueOf(c));
            }
            body = quoted.toString();
            prefilter = xpath.toString();
        } else {
            body = Pattern.quote(trimmed);
            prefilter = null;
        }
        if (wholeWords) {
            body = "(?<![\\p{L}\\p{N}_])(?:" + body + ")(?![\\p{L}\\p{N}_])";
        }
        int flags = Pattern.UNICODE_CASE | (caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
        try {
            return new SearchMatcher(trimmed, Pattern.compile(body, flags), regex, caseSensitive,
                    wholeWords, prefilter);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regular expression: " + e.getDescription());
        }
    }

    public boolean isWholeWords() {
        return wholeWords;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public String prefilterRegex() {
        return prefilter;
    }

    public boolean isEmpty() {
        return raw.isEmpty();
    }

    public boolean isRegex() {
        return regex;
    }

    public String raw() {
        return raw;
    }

    public String lowerNeedle() {
        return raw.toLowerCase(Locale.ROOT);
    }

    public List<int[]> find(String text) {
        List<int[]> ranges = new ArrayList<>();
        if (raw.isEmpty() || text == null || text.isEmpty()) {
            return ranges;
        }
        if (pattern == null) {
            int len = raw.length();
            int from = 0;
            while (ranges.size() < MAX_RANGES_PER_VALUE && from + len <= text.length()) {
                if (text.regionMatches(!caseSensitive, from, raw, 0, len)) {
                    ranges.add(new int[] {from, from + len});
                    from += len;
                } else {
                    from++;
                }
            }
            return ranges;
        }
        Matcher matcher = pattern.matcher(new DeadlineCharSequence(text, System.nanoTime() + REGEX_BUDGET_NANOS));
        while (ranges.size() < MAX_RANGES_PER_VALUE && matcher.find()) {
            if (matcher.end() > matcher.start()) {
                ranges.add(new int[] {matcher.start(), matcher.end()});
            }
        }
        return ranges;
    }

    public static final class SearchTimeoutException extends RuntimeException {
        public SearchTimeoutException() {
            super("Regular expression took too long to evaluate");
        }
    }

    private static final class DeadlineCharSequence implements CharSequence {
        private final CharSequence inner;
        private final long deadline;
        private int calls;

        DeadlineCharSequence(CharSequence inner, long deadline) {
            this.inner = inner;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if ((++calls & 0xFFF) == 0 && System.nanoTime() > deadline) {
                throw new SearchTimeoutException();
            }
            return inner.charAt(index);
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(inner.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
