package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqwrlPagerTest {

    private static SqwrlResultStore.Entry entry(String[][] data) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String[] d : data) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("p", d[0]);
            row.put("age", d[1]);
            rows.add(row);
        }
        return new SqwrlResultStore.Entry("id", "p1", "u1", Instant.now(), List.of("p", "age"), rows, 0,
                new SqwrlResultStore.Meta("q", "x", 1, rows.size(), true, false, 1000, false));
    }

    private static final String[][] DATA = {{"Ann", "30"}, {"bob", "4"}, {"Cy", "100"}, {"Dee", "30"}};

    private static List<String> names(SqwrlPager.Page page) {
        return page.rows().stream().map(r -> r.get("p")).toList();
    }

    @Test
    void filterIsCaseInsensitiveOverAllColumns() {
        SqwrlResultStore.Entry e = entry(DATA);
        assertEquals(4, SqwrlPager.page(e, "", null, false, 0, 100, 1 << 20).filteredRows());
        assertEquals(List.of("bob"), names(SqwrlPager.page(e, "BOB", null, false, 0, 100, 1 << 20)));
        assertEquals(2, SqwrlPager.page(e, "30", null, false, 0, 100, 1 << 20).filteredRows());
        assertEquals(0, SqwrlPager.page(e, "zzz", null, false, 0, 100, 1 << 20).filteredRows());
    }

    @Test
    void sortIsNumericAwareAndStable() {
        SqwrlResultStore.Entry e = entry(DATA);
        assertEquals(List.of("bob", "Ann", "Dee", "Cy"), names(SqwrlPager.page(e, null, "age", false, 0, 100, 1 << 20)));
        assertEquals(List.of("Cy", "Ann", "Dee", "bob"), names(SqwrlPager.page(e, null, "age", true, 0, 100, 1 << 20)));
        assertEquals(List.of("Ann", "bob", "Cy", "Dee"), names(SqwrlPager.page(e, null, "p", false, 0, 100, 1 << 20)));
    }

    @Test
    void pagingReturnsOffsetsAndNextOffset() {
        SqwrlResultStore.Entry e = entry(DATA);
        SqwrlPager.Page first = SqwrlPager.page(e, null, null, false, 0, 3, 1 << 20);
        assertEquals(3, first.rows().size());
        assertEquals(3, first.nextOffset());
        SqwrlPager.Page second = SqwrlPager.page(e, null, null, false, 3, 3, 1 << 20);
        assertEquals(1, second.rows().size());
        assertEquals(4, second.filteredRows());
        assertTrue(SqwrlPager.page(e, null, null, false, 99, 3, 1 << 20).rows().isEmpty());
    }

    @Test
    void thePageByteCapCutsThePageAndFlagsIt() {
        SqwrlResultStore.Entry e = entry(DATA);
        SqwrlPager.Page page = SqwrlPager.page(e, null, null, false, 0, 100, 8);
        assertTrue(page.pageBytesCapped());
        assertTrue(page.rows().size() < 4);
        assertFalse(page.rows().isEmpty());
        assertEquals(page.rows().size(), page.nextOffset());
    }
}
