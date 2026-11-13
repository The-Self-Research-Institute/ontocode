package self.research.ontology.swrl.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.testing.EmbeddedTestDatabase;
import self.research.ontology.swrl.model.SwrlRule;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcSwrlRuleRepositoryTest {

    private final JdbcTemplate jdbc = EmbeddedTestDatabase.jdbc();
    private final JdbcSwrlRuleRepository rules = new JdbcSwrlRuleRepository(jdbc);

    @BeforeEach
    void clean() {
        jdbc.update("delete from swrl_rule");
    }

    private static SwrlRule rule(String project, String name) {
        SwrlRule rule = new SwrlRule(project, name, "Person(?p) -> Adult(?p)");
        rule.setComment("a comment");
        rule.setCategory("people");
        rule.setCreatedBy("alice");
        rule.setUpdatedBy("bob");
        rule.setExecutionCount(4);
        rule.setLastExecutionTime(120L);
        rule.setLastExecutedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        rule.setValidated(true);
        rule.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        rule.setUpdatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        return rule;
    }

    @Test
    void savingARuleWithoutAnIdGivesItAnObjectIdShapedIdAndEveryFieldRoundTrips() {
        SwrlRule saved = rules.save(rule("p1", "adult"));

        assertTrue(Ids.looksLikeObjectId(saved.getId()));
        SwrlRule loaded = rules.findByIdAndProjectId(saved.getId(), "p1").orElseThrow();
        assertEquals("adult", loaded.getRuleName());
        assertEquals("Person(?p) -> Adult(?p)", loaded.getRuleText());
        assertEquals("a comment", loaded.getComment());
        assertEquals("people", loaded.getCategory());
        assertEquals("alice", loaded.getCreatedBy());
        assertEquals("bob", loaded.getUpdatedBy());
        assertEquals(4, loaded.getExecutionCount());
        assertEquals(120L, loaded.getLastExecutionTime());
        assertEquals(saved.getCreatedAt(), loaded.getCreatedAt());
        assertEquals(saved.getUpdatedAt(), loaded.getUpdatedAt());
        assertEquals(saved.getLastExecutedAt(), loaded.getLastExecutedAt());
        assertTrue(loaded.isValidated());
        assertTrue(loaded.isEnabled());
        assertNull(loaded.getMigratedAt());
        assertNull(loaded.getValidationError());
    }

    @Test
    void aNullLastExecutionTimeStaysNull() {
        SwrlRule r = rule("p1", "n");
        r.setLastExecutionTime(null);
        SwrlRule saved = rules.save(r);

        assertNull(rules.findByIdAndProjectId(saved.getId(), "p1").orElseThrow().getLastExecutionTime());
    }

    @Test
    void savingAgainWithTheSameIdUpdatesTheRuleInsteadOfAddingOne() {
        SwrlRule saved = rules.save(rule("p1", "adult"));
        saved.setRuleText("changed");
        saved.setEnabled(false);
        rules.save(saved);

        assertEquals(1, jdbc.queryForObject("select count(*) from swrl_rule", Integer.class));
        SwrlRule loaded = rules.findByIdAndProjectId(saved.getId(), "p1").orElseThrow();
        assertEquals("changed", loaded.getRuleText());
        assertEquals(false, loaded.isEnabled());
    }

    @Test
    void aRuleIsOnlyFoundInItsOwnProject() {
        SwrlRule saved = rules.save(rule("p1", "adult"));

        assertEquals(Optional.empty(), rules.findByIdAndProjectId(saved.getId(), "other"));
        assertNotNull(rules.findByIdAndProjectId(saved.getId(), "p1").orElse(null));
    }

    @Test
    void theSameRuleNameCannotBeUsedTwiceInOneProjectButCanInAnother() {
        rules.save(rule("p1", "adult"));
        rules.save(rule("p2", "adult"));

        assertThrows(DuplicateKeyException.class, () -> rules.save(rule("p1", "adult")));
        assertEquals(2, jdbc.queryForObject("select count(*) from swrl_rule", Integer.class));
    }

    @Test
    void legacyRulesAreTheOnesNotYetMigratedInThatProject() {
        SwrlRule a = rules.save(rule("p1", "a"));
        SwrlRule b = rules.save(rule("p1", "b"));
        rules.save(rule("p2", "c"));
        b.setMigratedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        rules.save(b);

        List<SwrlRule> legacy = rules.findByProjectIdAndMigratedAtIsNull("p1");

        assertEquals(1, legacy.size());
        assertEquals(a.getId(), legacy.get(0).getId());
        assertEquals(List.of(), rules.findByProjectIdAndMigratedAtIsNull("none"));
    }

    @Test
    void aMigratedRuleKeepsItsMigrationTimeWhenReadBack() {
        SwrlRule r = rule("p1", "a");
        LocalDateTime when = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        r.setMigratedAt(when);
        SwrlRule saved = rules.save(r);

        assertEquals(when, rules.findByIdAndProjectId(saved.getId(), "p1").orElseThrow().getMigratedAt());
    }
}
