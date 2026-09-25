package self.research.ontology.owlEditor.service;

import self.research.ontology.owlEditor.util.SubjectRangeIndex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

record PatchSpans(List<long[]> oldSpans, List<long[]> newSpans) {

    private static final int MAX_ROUNDS = 20;

    private record Shift(long oldStart, int oldCount, long newStart, int newCount) {}

    long totalOldLines() {
        long total = 0;
        for (long[] span : oldSpans) {
            total += span[1] - span[0] + 1;
        }
        return total;
    }

    static PatchSpans compute(List<TriplePatchPlanner.Edit> edits, SubjectRangeIndex oldIndex, SubjectRangeIndex newIndex) {
        List<Shift> shifts = shifts(edits);
        List<long[]> old = new ArrayList<>();
        for (Shift shift : shifts) {
            if (touchesHeader(oldIndex, shift.oldStart, shift.oldCount)
                    || touchesHeader(newIndex, shift.newStart, shift.newCount)) {
                return null;
            }
            old.add(new long[]{Math.max(0, shift.oldStart - 1), shift.oldStart + Math.max(shift.oldCount, 1)});
        }
        old = merge(old);
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<long[]> expandedOld = merge(expand(old, oldIndex));
            List<long[]> mapped = new ArrayList<>();
            for (long[] span : expandedOld) {
                mapped.add(new long[]{toNew(shifts, span[0], true), toNew(shifts, span[1], false)});
            }
            List<long[]> expandedNew = merge(expand(mapped, newIndex));
            List<long[]> back = new ArrayList<>(expandedOld);
            for (long[] span : expandedNew) {
                back.add(new long[]{toOld(shifts, span[0], true), toOld(shifts, span[1], false)});
            }
            back = merge(back);
            if (sameSpans(back, old) && sameSpans(back, expandedOld)) {
                return new PatchSpans(back, expandedNew);
            }
            old = back;
        }
        return null;
    }

    private static boolean touchesHeader(SubjectRangeIndex index, long start, int count) {
        return count > 0 && index.touchesHeader(start, start + count - 1);
    }

    private static List<Shift> shifts(List<TriplePatchPlanner.Edit> edits) {
        List<TriplePatchPlanner.Edit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingLong(TriplePatchPlanner.Edit::startLine));
        List<Shift> shifts = new ArrayList<>();
        long delta = 0;
        for (TriplePatchPlanner.Edit edit : sorted) {
            shifts.add(new Shift(edit.startLine(), edit.lineCount(), edit.startLine() + delta, edit.newLineCount()));
            delta += edit.newLineCount() - edit.lineCount();
        }
        return shifts;
    }

    static long toNew(List<Shift> shifts, long line, boolean start) {
        long delta = 0;
        for (Shift shift : shifts) {
            if (shift.oldCount == 0) {
                if (line < shift.oldStart || (start && line == shift.oldStart)) {
                    break;
                }
                delta += shift.newCount;
                continue;
            }
            if (line < shift.oldStart) {
                break;
            }
            if (line < shift.oldStart + shift.oldCount) {
                return start ? shift.newStart : shift.newStart + shift.newCount - 1;
            }
            delta += shift.newCount - shift.oldCount;
        }
        return line + delta;
    }

    static long toOld(List<Shift> shifts, long line, boolean start) {
        long delta = 0;
        for (Shift shift : shifts) {
            if (shift.newCount == 0) {
                if (line < shift.newStart || (start && line == shift.newStart)) {
                    break;
                }
                delta += shift.oldCount;
                continue;
            }
            if (line < shift.newStart) {
                break;
            }
            if (line < shift.newStart + shift.newCount) {
                return start ? shift.oldStart : shift.oldStart + Math.max(shift.oldCount, 1) - 1;
            }
            delta += shift.oldCount - shift.newCount;
        }
        return line + delta;
    }

    private static List<long[]> expand(List<long[]> spans, SubjectRangeIndex index) {
        List<long[]> result = new ArrayList<>();
        for (long[] span : spans) {
            long from = span[0];
            long to = span[1];
            for (SubjectRangeIndex.Block block : index.overlapping(span[0], span[1])) {
                from = Math.min(from, block.startLine());
                to = Math.max(to, block.endLine());
            }
            result.add(new long[]{from, to});
        }
        return result;
    }

    private static List<long[]> merge(List<long[]> spans) {
        List<long[]> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingLong(span -> span[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] span : sorted) {
            long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && span[0] <= last[1] + 1) {
                last[1] = Math.max(last[1], span[1]);
            } else {
                merged.add(new long[]{span[0], span[1]});
            }
        }
        return merged;
    }

    private static boolean sameSpans(List<long[]> a, List<long[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i)[0] != b.get(i)[0] || a.get(i)[1] != b.get(i)[1]) {
                return false;
            }
        }
        return true;
    }
}
