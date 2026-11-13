package self.research.ontology.migrator.core;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueCompareTest {

    @Test
    void nanosecondsInTheSourceMatchTheMicrosecondsPostgresKeeps() {
        OffsetDateTime source = OffsetDateTime.parse("2026-10-05T05:35:27.092781198Z");
        OffsetDateTime stored = OffsetDateTime.parse("2026-10-05T05:35:27.092781Z");
        assertTrue(ValueCompare.same(source, stored));
    }

    @Test
    void roundingUpToTheNextMicrosecondStillMatches() {
        OffsetDateTime source = OffsetDateTime.parse("2026-10-05T05:35:27.092781900Z");
        OffsetDateTime stored = OffsetDateTime.parse("2026-10-05T05:35:27.092782Z");
        assertTrue(ValueCompare.same(source, stored));
    }

    @Test
    void aRealDifferenceInTimeIsStillReported() {
        OffsetDateTime source = OffsetDateTime.parse("2026-10-05T05:35:27.092781198Z");
        OffsetDateTime stored = OffsetDateTime.parse("2026-10-05T05:35:27.092800Z");
        assertFalse(ValueCompare.same(source, stored));
        assertFalse(ValueCompare.same(source, OffsetDateTime.parse("2026-10-05T05:35:28Z")));
    }

    @Test
    void textThatHadNulCharactersMatchesTheStoredCopyWithoutThem() {
        assertTrue(ValueCompare.same("mark\u0000er", "marker"));
        assertFalse(ValueCompare.same("marker", "markers"));
    }

    @Test
    void jsonThatHadNulEscapesMatchesTheStoredCopyWithoutThem() {
        assertTrue(ValueCompare.same(new Write.Json("{\"a\":\"x\\u0000y\"}"), "{\"a\": \"xy\"}"));
        assertFalse(ValueCompare.same(new Write.Json("{\"a\":\"x\\u0000y\"}"), "{\"a\": \"xz\"}"));
    }
}
