package self.research.ontology.migrator;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import self.research.ontology.migrator.collections.UserMigration;
import self.research.ontology.migrator.core.DedupPlan;
import self.research.ontology.migrator.core.MigrationReport;
import self.research.ontology.migrator.core.TargetReader;
import self.research.ontology.migrator.core.ValueCompare;
import self.research.ontology.migrator.core.Verification;
import self.research.ontology.migrator.core.Write;
import self.research.ontology.migrator.support.Docs;
import self.research.ontology.migrator.support.InMemorySource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationTest {

    private static final class FakeTarget implements TargetReader {
        final Map<String, Map<String, Object>> rows = new HashMap<>();

        @Override
        public Optional<Map<String, Object>> fetch(String table, List<String> keyColumns, List<Object> keyValues) {
            return Optional.ofNullable(rows.get(table + "/" + keyValues.get(0)));
        }
    }

    private static FakeTarget targetHolding(InMemorySource source) {
        FakeTarget target = new FakeTarget();
        try (var cursor = source.scan("users", null, 10)) {
            while (cursor.hasNext()) {
                for (Write w : new UserMigration().map(cursor.next())) {
                    Write.Upsert u = (Write.Upsert) w;
                    target.rows.put(u.table() + "/" + u.values().get("id"), new HashMap<>(u.values()));
                }
            }
        }
        return target;
    }

    @Test
    void identicalDataPasses() {
        InMemorySource source = new InMemorySource().with("users", Docs.user(1, "a@x.com", "2026-01-01T00:00:00Z"), Docs.user(2, "b@x.com", "2026-01-01T00:00:00Z"));
        MigrationReport.VerifyStats stats = new Verification(source, targetHolding(source), 10, 200).verify(new UserMigration(), DedupPlan.empty());
        assertEquals(2, stats.keysChecked);
        assertEquals(2, stats.sampled);
        assertTrue(stats.reconciled());
    }

    @Test
    void aMissingRowIsFoundByTheKeyCheck() {
        InMemorySource source = new InMemorySource().with("users", Docs.user(1, "a@x.com", "2026-01-01T00:00:00Z"), Docs.user(2, "b@x.com", "2026-01-01T00:00:00Z"));
        FakeTarget target = targetHolding(source);
        target.rows.remove("app_user/" + Docs.oid(2).toHexString());
        MigrationReport.VerifyStats stats = new Verification(source, target, 10, 200).verify(new UserMigration(), DedupPlan.empty());
        assertEquals(1, stats.keysMissing);
        assertFalse(stats.reconciled());
    }

    @Test
    void aChangedFieldIsFoundByTheSampleCompare() {
        InMemorySource source = new InMemorySource().with("users", Docs.user(1, "a@x.com", "2026-01-01T00:00:00Z"));
        FakeTarget target = targetHolding(source);
        target.rows.get("app_user/" + Docs.oid(1).toHexString()).put("email", "tampered@x.com");
        target.rows.get("app_user/" + Docs.oid(1).toHexString()).put("created_at", OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        MigrationReport.VerifyStats stats = new Verification(source, target, 10, 200).verify(new UserMigration(), DedupPlan.empty());
        assertEquals(2, stats.fieldMismatches);
        assertTrue(stats.examples.stream().anyMatch(e -> e.contains("app_user.email")));
    }

    @Test
    void droppedDuplicatesAreNotExpectedInTheTarget() {
        InMemorySource source = new InMemorySource().with("users", Docs.user(1, "A@x.com", "2026-01-01T00:00:00Z"), Docs.user(2, "a@x.com", "2026-02-01T00:00:00Z"));
        DedupPlan plan = DedupPlan.build(source, new UserMigration(), 10);
        FakeTarget target = targetHolding(source);
        target.rows.remove("app_user/" + Docs.oid(1).toHexString());
        MigrationReport.VerifyStats stats = new Verification(source, target, 10, 200).verify(new UserMigration(), plan);
        assertTrue(stats.reconciled());
        assertEquals(1, stats.keysChecked);
    }

    @Test
    void theSampleSizeLimitsFieldComparisons() {
        InMemorySource source = new InMemorySource();
        for (int i = 1; i <= 20; i++) {
            source.with("users", Docs.user(i, "u" + i + "@x.com", "2026-01-01T00:00:00Z"));
        }
        MigrationReport.VerifyStats stats = new Verification(source, targetHolding(source), 10, 5).verify(new UserMigration(), DedupPlan.empty());
        assertEquals(20, stats.keysChecked);
        assertEquals(5, stats.sampled);
    }

    @Test
    void valueComparisonUnderstandsTheColumnTypes() {
        assertTrue(ValueCompare.same(OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.ofHours(5)), OffsetDateTime.of(2026, 1, 1, 7, 0, 0, 0, ZoneOffset.UTC)));
        assertTrue(ValueCompare.same(new Write.Json("{\"a\":1,\"b\":[1,2]}"), "{\"b\": [1, 2], \"a\": 1}"));
        assertFalse(ValueCompare.same(new Write.Json("{\"a\":1}"), "{\"a\":2}"));
        assertTrue(ValueCompare.same(List.of("x", "y"), List.of("x", "y")));
        assertFalse(ValueCompare.same(List.of("x", "y"), List.of("y", "x")));
        assertTrue(ValueCompare.same(4, 4L));
        assertTrue(ValueCompare.same(4.5, new java.math.BigDecimal("4.50")));
        assertTrue(ValueCompare.same(null, null));
        assertFalse(ValueCompare.same("a", null));
    }

    @Test
    void documentsThatCannotBeMappedAreLeftToTheRunReport() {
        InMemorySource source = new InMemorySource().with("users", new Document("_id", "broken"));
        MigrationReport.VerifyStats stats = new Verification(source, new FakeTarget(), 10, 200).verify(new UserMigration(), DedupPlan.empty());
        assertTrue(stats.reconciled());
    }
}
