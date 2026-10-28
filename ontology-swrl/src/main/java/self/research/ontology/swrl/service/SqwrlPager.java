package self.research.ontology.swrl.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class SqwrlPager {

    public static final int MAX_FILTER_LENGTH = 200;

    private static final Pattern NUMBER = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");

    public record Page(List<Map<String, String>> rows, int filteredRows, int offset, int nextOffset, boolean pageBytesCapped) {
    }

    private SqwrlPager() {
    }

    public static Page page(SqwrlResultStore.Entry entry, String filter, String sortColumn, boolean descending,
                            int offset, int limit, long maxPageBytes) {
        List<Map<String, String>> view = filtered(entry, filter);
        if (sortColumn != null && entry.columns().contains(sortColumn)) {
            Comparator<Map<String, String>> by = (a, b) -> compareCells(text(a.get(sortColumn)), text(b.get(sortColumn)));
            view = new ArrayList<>(view);
            view.sort(descending ? by.reversed() : by);
        }
        int start = Math.min(Math.max(0, offset), view.size());
        int end = (int) Math.min((long) start + Math.max(0, limit), view.size());
        List<Map<String, String>> out = new ArrayList<>();
        long bytes = 0;
        boolean capped = false;
        for (int i = start; i < end; i++) {
            Map<String, String> row = view.get(i);
            long rowBytes = 0;
            for (String cell : row.values()) {
                rowBytes += cell == null ? 0 : cell.length();
            }
            if (!out.isEmpty() && bytes + rowBytes > maxPageBytes) {
                capped = true;
                break;
            }
            bytes += rowBytes;
            out.add(row);
        }
        return new Page(out, view.size(), start, start + out.size(), capped);
    }

    static List<Map<String, String>> filtered(SqwrlResultStore.Entry entry, String filter) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        if (needle.length() > MAX_FILTER_LENGTH) {
            needle = needle.substring(0, MAX_FILTER_LENGTH);
        }
        if (needle.isEmpty()) {
            return entry.rows();
        }
        List<Map<String, String>> hits = new ArrayList<>();
        for (Map<String, String> row : entry.rows()) {
            for (String column : entry.columns()) {
                if (text(row.get(column)).toLowerCase(Locale.ROOT).contains(needle)) {
                    hits.add(row);
                    break;
                }
            }
        }
        return hits;
    }

    static int compareCells(String a, String b) {
        Double na = number(a);
        Double nb = number(b);
        if (na != null && nb != null) {
            return Double.compare(na, nb);
        }
        if (na != null) {
            return -1;
        }
        if (nb != null) {
            return 1;
        }
        return a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT));
    }

    private static Double number(String text) {
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

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
