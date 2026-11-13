package self.research.ontology.common.db.sql;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlHelpersTest {

    @Test
    void anUpsertIsBuiltFromTheColumnsAndKeyAndUpdatesEveryNonKeyColumn() {
        SqlUpsert upsert = SqlUpsert.into("reasoning_job").key("job_id").columns("job_id", "status", "updated_at").build();

        assertEquals("insert into reasoning_job (job_id, status, updated_at) values (?, ?, ?) "
                + "on conflict (job_id) do update set status = excluded.status, updated_at = excluded.updated_at", upsert.sql());
        assertEquals(3, upsert.columnCount());
    }

    @Test
    void anUpsertWithACompositeKeyAndNothingElseToUpdateDoesNothingOnConflict() {
        SqlUpsert upsert = SqlUpsert.into("link").key("a", "b").columns("a", "b").build();

        assertEquals("insert into link (a, b) values (?, ?) on conflict (a, b) do nothing", upsert.sql());
    }

    @Test
    void anUpsertRejectsUnsafeNamesAndAKeyThatIsNotAColumn() {
        assertThrows(IllegalArgumentException.class, () -> SqlUpsert.into("x; drop table y"));
        assertThrows(IllegalArgumentException.class, () -> SqlUpsert.into("t").key("a b"));
        assertThrows(IllegalStateException.class, () -> SqlUpsert.into("t").key("id").columns("name").build());
        assertThrows(IllegalStateException.class, () -> SqlUpsert.into("t").build());
    }

    @Test
    void placeholdersAndChunksBehaveAtTheEdges() {
        assertEquals("?", SqlLists.placeholders(1));
        assertEquals("?, ?, ?", SqlLists.placeholders(3));
        assertThrows(IllegalArgumentException.class, () -> SqlLists.placeholders(0));

        List<List<Integer>> chunks = new ArrayList<>();
        SqlLists.inChunks(List.of(1, 2, 3, 4, 5), 2, chunks::add);
        assertEquals(List.of(List.of(1, 2), List.of(3, 4), List.of(5)), chunks);
        chunks.clear();
        SqlLists.inChunks(List.<Integer>of(), 2, chunks::add);
        assertEquals(List.of(), chunks);
        assertThrows(IllegalArgumentException.class, () -> SqlLists.inChunks(List.of(1), 0, c -> { }));
    }

    @Test
    void instantsConvertBothWaysAndKeepNull() {
        Instant now = Instant.parse("2026-11-05T10:15:30.123456Z");

        assertEquals(now, Instants.fromDb(Instants.toDb(now)));
        assertNull(Instants.toDb((Instant) null));
        assertNull(Instants.fromDb(null));
    }
}
