package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlRunPagerTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final long BIG = 8L << 20;

    private SparqlRunStore.Entry entry;

    @BeforeEach
    void setUp() {
        List<BindingSet> rows = new ArrayList<>();
        int[] numbers = {10, 9, 100, 2, 33};
        String[] names = {"Banana", "apple", "Cherry", "date", "Elder"};
        for (int i = 0; i < numbers.length; i++) {
            MapBindingSet row = new MapBindingSet();
            row.addBinding("name", VF.createLiteral(names[i]));
            row.addBinding("n", VF.createLiteral(numbers[i]));
            if (i != 3) {
                row.addBinding("iri", VF.createIRI("http://example.org/item/" + names[i]));
            }
            rows.add(row);
        }
        SparqlRunStore store = new SparqlRunStore(Clock.systemUTC(), Duration.ofMinutes(15), 5, 50, 64L << 20, 1000, 16L << 20);
        entry = store.put("p", "u", List.of("name", "n", "iri"), rows, 5, false, 1);
    }

    private static List<String> column(SparqlRunPager.Page page, int index) {
        List<String> out = new ArrayList<>();
        for (String[] row : page.rows()) {
            out.add(SparqlRunStore.display(row[index]));
        }
        return out;
    }

    @Test
    void pagesInStoredOrderWithoutFilterOrSort() {
        SparqlRunPager.Page page = SparqlRunPager.page(entry, null, null, false, 1, 2, BIG);
        assertEquals(List.of("apple", "Cherry"), column(page, 0));
        assertEquals(5, page.filteredRows());
        assertEquals(1, page.offset());
        assertEquals(3, page.nextOffset());
        assertFalse(page.pageBytesCapped());
    }

    @Test
    void filtersCaseInsensitivelyOverEveryCell() {
        SparqlRunPager.Page byName = SparqlRunPager.page(entry, "AN", null, false, 0, 10, BIG);
        assertEquals(List.of("Banana"), column(byName, 0));
        SparqlRunPager.Page byNumber = SparqlRunPager.page(entry, "100", null, false, 0, 10, BIG);
        assertEquals(List.of("Cherry"), column(byNumber, 0));
        SparqlRunPager.Page byIri = SparqlRunPager.page(entry, "/ITEM/c", null, false, 0, 10, BIG);
        assertEquals(List.of("Cherry"), column(byIri, 0));
        assertEquals(0, SparqlRunPager.page(entry, "zzz", null, false, 0, 10, BIG).filteredRows());
    }

    @Test
    void sortsNumbersAsNumbersAndTextIgnoringCase() {
        assertEquals(List.of("2", "9", "10", "33", "100"),
                column(SparqlRunPager.page(entry, null, "n", false, 0, 10, BIG), 1));
        assertEquals(List.of("100", "33", "10", "9", "2"),
                column(SparqlRunPager.page(entry, null, "n", true, 0, 10, BIG), 1));
        assertEquals(List.of("apple", "Banana", "Cherry", "date", "Elder"),
                column(SparqlRunPager.page(entry, null, "name", false, 0, 10, BIG), 0));
    }

    @Test
    void sortAndFilterCombineAndOffsetCountsFilteredRows() {
        SparqlRunPager.Page page = SparqlRunPager.page(entry, "e", "n", true, 1, 2, BIG);
        assertEquals(5, page.filteredRows());
        assertEquals(List.of("Elder", "Banana"), column(page, 0));
    }

    @Test
    void anUnknownSortColumnKeepsStoredOrder() {
        assertEquals(List.of("Banana", "apple", "Cherry", "date", "Elder"),
                column(SparqlRunPager.page(entry, null, "nope", false, 0, 10, BIG), 0));
    }

    @Test
    void anOffsetPastTheEndGivesAnEmptyPage() {
        SparqlRunPager.Page page = SparqlRunPager.page(entry, null, null, false, 50, 10, BIG);
        assertTrue(page.rows().isEmpty());
        assertEquals(5, page.filteredRows());
        assertEquals(5, page.offset());
    }

    @Test
    void aPageStopsAtTheByteCapButAlwaysReturnsOneRow() {
        SparqlRunPager.Page page = SparqlRunPager.page(entry, null, null, false, 0, 10, 1);
        assertEquals(1, page.rows().size());
        assertTrue(page.pageBytesCapped());
        assertEquals(1, page.nextOffset());
        assertEquals(5, page.filteredRows());
    }

    @Test
    void missingCellsAreSkippedByTheFilterAndSortAsEmpty() {
        SparqlRunPager.Page page = SparqlRunPager.page(entry, null, "iri", false, 0, 10, BIG);
        assertEquals("date", SparqlRunStore.display(page.rows().get(0)[0]));
    }
}
