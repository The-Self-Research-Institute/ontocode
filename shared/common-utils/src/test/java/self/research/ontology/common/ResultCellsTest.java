package self.research.ontology.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultCellsTest {

    @Test
    void numbersAreRecognisedOnlyWhenWholeCellIsNumeric() {
        assertEquals(10.0, ResultCells.number("10"));
        assertEquals(-2.5, ResultCells.number(" -2.5 "));
        assertEquals(1000.0, ResultCells.number("1e3"));
        assertNull(ResultCells.number("10 apples"));
        assertNull(ResultCells.number(""));
        assertNull(ResultCells.number("NaN"));
    }

    @Test
    void numbersSortBeforeTextAndNumbersCompareNumerically() {
        assertTrue(ResultCells.compareCells("9", "10") < 0);
        assertTrue(ResultCells.compareCells("100", "abc") < 0);
        assertTrue(ResultCells.compareCells("abc", "100") > 0);
        assertTrue(ResultCells.compareCells("apple", "Banana") < 0);
        assertEquals(0, ResultCells.compareCells("Same", "same"));
    }

    @Test
    void theFilterIsTrimmedLowercasedAndCapped() {
        assertEquals("abc", ResultCells.normalizeFilter("  ABC "));
        assertEquals("", ResultCells.normalizeFilter(null));
        assertEquals(ResultCells.MAX_FILTER_LENGTH, ResultCells.normalizeFilter("x".repeat(500)).length());
    }

    @Test
    void containsIgnoresCaseAndHonoursTheStartOffset() {
        assertTrue(ResultCells.containsIgnoreCase("Hello World", 0, "world"));
        assertFalse(ResultCells.containsIgnoreCase("Hello World", 7, "hello"));
        assertTrue(ResultCells.containsIgnoreCase("abc", 0, ""));
        assertFalse(ResultCells.containsIgnoreCase("ab", 0, "abc"));
    }
}
