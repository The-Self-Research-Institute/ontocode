package self.research.ontology.owlEditor.service;

import self.research.ontology.common.ResultCells;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SparqlRunPager {

    public record Page(List<String[]> rows, int filteredRows, int offset, int nextOffset, boolean pageBytesCapped) {
    }

    private SparqlRunPager() {
    }

    public static Page page(SparqlRunStore.Entry entry, String filter, String sortColumn, boolean descending,
                            int offset, int limit, long maxPageBytes) {
        String[][] rows = entry.rows();
        String needle = ResultCells.normalizeFilter(filter);
        int column = sortColumn == null ? -1 : entry.vars().indexOf(sortColumn);
        int[] order = column < 0 ? null : ascendingOrder(entry, column);
        int start = Math.max(0, offset);
        int end = (int) Math.min((long) start + Math.max(0, limit), Integer.MAX_VALUE);
        List<String[]> out = new ArrayList<>();
        long bytes = 0;
        boolean capped = false;
        int matched = 0;
        for (int i = 0; i < rows.length; i++) {
            int index = order == null ? i : (descending ? order[rows.length - 1 - i] : order[i]);
            String[] row = rows[index];
            if (!needle.isEmpty() && !matches(row, needle)) {
                continue;
            }
            if (matched >= start && matched < end && !capped) {
                long rowBytes = 0;
                for (String cell : row) {
                    rowBytes += cell == null ? 0 : cell.length();
                }
                if (!out.isEmpty() && bytes + rowBytes > maxPageBytes) {
                    capped = true;
                } else {
                    bytes += rowBytes;
                    out.add(row);
                }
            }
            matched++;
        }
        int first = Math.min(start, matched);
        return new Page(out, matched, first, first + out.size(), capped);
    }

    static boolean matches(String[] row, String lowerNeedle) {
        for (String cell : row) {
            if (cell == null) {
                continue;
            }
            if (cell.charAt(0) == 'b') {
                if (ResultCells.containsIgnoreCase("_:" + cell.substring(1), 0, lowerNeedle)) {
                    return true;
                }
            } else if (ResultCells.containsIgnoreCase(cell, SparqlRunStore.displayStart(cell), lowerNeedle)) {
                return true;
            }
        }
        return false;
    }

    private static int[] ascendingOrder(SparqlRunStore.Entry entry, int column) {
        SparqlRunStore.SortOrder cached = entry.sortOrder();
        if (cached != null && cached.column() == column) {
            return cached.ascending();
        }
        String[][] rows = entry.rows();
        Double[] numbers = new Double[rows.length];
        String[] lowers = new String[rows.length];
        for (int i = 0; i < rows.length; i++) {
            String cell = rows[i][column];
            String text = cell == null ? "" : SparqlRunStore.display(cell);
            numbers[i] = ResultCells.number(text);
            lowers[i] = text.toLowerCase(Locale.ROOT);
        }
        Integer[] boxed = new Integer[rows.length];
        for (int i = 0; i < boxed.length; i++) {
            boxed[i] = i;
        }
        java.util.Arrays.sort(boxed, (a, b) -> ResultCells.compare(numbers[a], lowers[a], numbers[b], lowers[b]));
        int[] order = new int[boxed.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = boxed[i];
        }
        entry.sortOrder(new SparqlRunStore.SortOrder(column, order));
        return order;
    }
}
