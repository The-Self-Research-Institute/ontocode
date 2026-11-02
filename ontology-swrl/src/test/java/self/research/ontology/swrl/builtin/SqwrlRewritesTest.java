package self.research.ontology.swrl.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Query operators the engine lacks are rewritten into the ones it has")
class SqwrlRewritesTest {

    @Test
    void positionOperatorsBecomeTheOnesTheEngineHandlesCorrectly() {
        assertEquals("A(?x) -> sqwrl:select(?x) ^ sqwrl:orderByDescending(?x) ^ sqwrl:nth(2)",
                SqwrlRewrites.sliceAliases("A(?x) -> sqwrl:select(?x) ^ sqwrl:orderBy(?x) ^ sqwrl:nthLast(2)"));
        assertEquals("A(?x) -> sqwrl:select(?x) ^ sqwrl:orderBy(?x) ^ sqwrl:nth(2)",
                SqwrlRewrites.sliceAliases("A(?x) -> sqwrl:select(?x) ^ sqwrl:orderByDescending(?x) ^ sqwrl:nthLast(2)"));
        assertEquals("A(?x) -> sqwrl:select(?x) ^ sqwrl:orderByDescending(?x) ^ sqwrl:nth(1)",
                SqwrlRewrites.sliceAliases("A(?x) -> sqwrl:select(?x) ^ sqwrl:orderBy(?x) ^ sqwrl:nthGreatest( 1 )"));
        assertEquals("A(?x) -> sqwrl:select(?x) ^ sqwrl:orderBy(?x) ^ sqwrl:notNthLastSlice(1, 1)",
                SqwrlRewrites.sliceAliases("A(?x) -> sqwrl:select(?x) ^ sqwrl:orderBy(?x) ^ sqwrl:notNthLast(1)"));
        String notSecondLast = "A(?x) -> sqwrl:select(?x) ^ sqwrl:orderBy(?x) ^ sqwrl:notNthLast(2)";
        assertEquals(notSecondLast, SqwrlRewrites.sliceAliases(notSecondLast));
    }

    @Test
    void operatorsTheEngineAlreadyKnowsAreLeftAlone() {
        String slice = "A(?x) -> sqwrl:select(?x) ^ sqwrl:orderBy(?x) ^ sqwrl:nthLastSlice(2, 1)";
        assertEquals(slice, SqwrlRewrites.sliceAliases(slice));
        String plain = "A(?x) -> sqwrl:select(?x) ^ sqwrl:orderBy(?x) ^ sqwrl:nth(2)";
        assertEquals(plain, SqwrlRewrites.sliceAliases(plain));
        assertEquals("A(?x) -> sqwrl:nthLast(?x)", SqwrlRewrites.sliceAliases("A(?x) -> sqwrl:nthLast(?x)"));
    }

    @Test
    void smallestAndBiggestOfTextOrDatesAreFoundByOrderingOnlyWhenTheNumberCheckFailed() {
        String failure = "sqwrl:min could not run: expecting numeric literal, got gamma";
        assertEquals("A(?x) ^ p(?x, ?p) -> sqwrl:select(?p) ^ sqwrl:orderBy(?p) ^ sqwrl:limit(1) ^ sqwrl:columnNames(\"min(?p)\")",
                SqwrlRewrites.minMaxByOrdering("A(?x) ^ p(?x, ?p) -> sqwrl:min(?p)", failure));
        assertEquals("A(?x) ^ p(?x, ?p) -> sqwrl:select(?p) ^ sqwrl:orderByDescending(?p) ^ sqwrl:limit(1) ^ sqwrl:columnNames(\"max(?p)\")",
                SqwrlRewrites.minMaxByOrdering("A(?x) ^ p(?x, ?p) -> sqwrl:max(?p)", failure));
        assertNull(SqwrlRewrites.minMaxByOrdering("A(?x) ^ p(?x, ?p) -> sqwrl:min(?p)", "some other failure"));
        assertNull(SqwrlRewrites.minMaxByOrdering("A(?x) ^ p(?x, ?p) -> sqwrl:select(?p) ^ sqwrl:min(?p)", failure));
    }
}
