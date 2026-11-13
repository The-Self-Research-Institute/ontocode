package self.research.ontology.common.db.sql;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdsAndLocalTimeTest {

    @Test
    void newIdsAre24HexCharactersAndAllDifferent() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            String id = Ids.newObjectId();
            assertEquals(24, id.length());
            assertTrue(Ids.looksLikeObjectId(id));
            assertTrue(seen.add(id), "duplicate id " + id);
        }
    }

    @Test
    void idsFromLaterSecondsSortAfterEarlierOnes() {
        String earlier = Ids.newObjectId(1_700_000_000L);
        String later = Ids.newObjectId(1_700_000_001L);

        assertTrue(earlier.compareTo(later) < 0);
        assertEquals("6553f100", earlier.substring(0, 8));
        assertEquals("6553f101", later.substring(0, 8));
    }

    @Test
    void looksLikeObjectIdRejectsOtherShapes() {
        assertTrue(Ids.looksLikeObjectId("507f1f77bcf86cd799439011"));
        assertFalse(Ids.looksLikeObjectId("507f1f77bcf86cd79943901"));
        assertFalse(Ids.looksLikeObjectId("507f1f77bcf86cd79943901g"));
        assertFalse(Ids.looksLikeObjectId("proj-1234"));
        assertFalse(Ids.looksLikeObjectId(null));
    }

    @Test
    void aLocalDateTimeKeepsItsInstantInTheServerZoneAndComesBackUnchanged() {
        LocalDateTime local = LocalDateTime.of(2026, 11, 5, 10, 15, 30, 123_456_000);

        OffsetDateTime stored = Instants.toDb(local);

        assertEquals(local.atZone(ZoneId.systemDefault()).toInstant(), stored.toInstant());
        assertEquals(local, stored.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
        assertEquals(null, Instants.toDb((LocalDateTime) null));
    }
}
