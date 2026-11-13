package self.research.ontology.owlEditor.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.ProjectScopedTables;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.model.DatatypeDefinitionEntity;
import self.research.ontology.owlEditor.model.FuzzyRuleEntity;
import self.research.ontology.owlEditor.model.IssueReport;
import self.research.ontology.owlEditor.model.SparqlQueryEntity;
import self.research.ontology.owlEditor.model.SpreadsheetImportRecord;
import self.research.ontology.owlEditor.model.SpreadsheetRuleSetEntity;
import self.research.ontology.owlEditor.model.SwrlRuleEntity;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleSpec;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcRulesAndRecordsRepositoriesTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();
    private final JdbcDatatypeDefinitionRepository datatypes = new JdbcDatatypeDefinitionRepository(jdbc);
    private final JdbcFuzzyRuleRepository fuzzy = new JdbcFuzzyRuleRepository(jdbc);
    private final JdbcSparqlQueryRepository queries = new JdbcSparqlQueryRepository(jdbc);
    private final JdbcIssueReportRepository issues = new JdbcIssueReportRepository(jdbc);
    private final JdbcSpreadsheetImportRecordRepository imports = new JdbcSpreadsheetImportRecordRepository(jdbc);
    private final JdbcSpreadsheetRuleSetRepository ruleSets = new JdbcSpreadsheetRuleSetRepository(jdbc);
    private final JdbcSwrlRuleRepository swrl = new JdbcSwrlRuleRepository(jdbc);

    @BeforeEach
    void clean() {
        for (String table : List.of("datatype_definition", "fuzzy_rule", "sparql_query", "issue_report",
                "spreadsheet_import_record", "spreadsheet_rule_set", "swrl_rule")) {
            jdbc.update("delete from " + table);
        }
    }

    private static DatatypeDefinitionEntity datatype(String project, String iri, boolean draft, String owner, long ageMs) {
        DatatypeDefinitionEntity e = new DatatypeDefinitionEntity();
        e.setProjectId(project);
        e.setDatatypeIri(iri);
        e.setExpression("xsd:integer[>= 1]");
        e.setDefinitionType("RESTRICTION");
        e.setDraft(draft);
        e.setOwnerUserId(owner);
        e.setCreatedAt(new Date(System.currentTimeMillis() - ageMs));
        e.setUpdatedAt(new Date(System.currentTimeMillis() - ageMs));
        return e;
    }

    @Test
    void datatypeDefinitionsFilterByDraftAndOwnerAndKeepRollbackMethods() {
        DatatypeDefinitionEntity a = datatypes.save(datatype("p1", "http://x/d", false, null, 3000));
        DatatypeDefinitionEntity b = datatypes.save(datatype("p1", "http://x/d", false, null, 1000));
        DatatypeDefinitionEntity mine = datatypes.save(datatype("p1", "http://x/d", true, "u1", 500));
        datatypes.save(datatype("p1", "http://x/d", true, "u2", 400));
        datatypes.save(datatype("p2", "http://x/d", false, null, 100));

        List<DatatypeDefinitionEntity> published = datatypes
                .findByProjectIdAndDatatypeIriAndDraftNotOrderByCreatedAtDesc("p1", "http://x/d", true);
        assertEquals(List.of(b.getId(), a.getId()), published.stream().map(DatatypeDefinitionEntity::getId).toList());
        assertEquals(1, datatypes.findByProjectIdAndDatatypeIriAndOwnerUserIdAndDraftTrue("p1", "http://x/d", "u1").size());
        assertEquals(1, datatypes.findByProjectIdAndOwnerUserIdAndDraftTrue("p1", "u2").size());
        assertTrue(datatypes.findByIdAndProjectId(mine.getId(), "p1").isPresent());
        assertTrue(datatypes.findByIdAndProjectId(mine.getId(), "p2").isEmpty());

        DatatypeDefinitionEntity loaded = datatypes.findById(a.getId()).orElseThrow();
        assertEquals("xsd:integer[>= 1]", loaded.getExpression());
        assertFalse(loaded.isDeleted());
        loaded.setDeleted(true);
        datatypes.save(loaded);
        assertTrue(datatypes.findById(a.getId()).orElseThrow().isDeleted());
        datatypes.deleteById(a.getId());
        assertTrue(datatypes.findById(a.getId()).isEmpty());
        datatypes.deleteAll(datatypes.findByProjectIdAndOwnerUserIdAndDraftTrue("p1", "u1"));
        assertTrue(datatypes.findById(mine.getId()).isEmpty());
    }

    @Test
    void fuzzyRulesAndSparqlQueriesRoundTrip() {
        FuzzyRuleEntity rule = new FuzzyRuleEntity();
        rule.setProjectId("p1");
        rule.setName("tall");
        rule.setCondition("height is high");
        rule.setAction("set tall");
        rule.setEnabled(false);
        fuzzy.save(rule);
        FuzzyRuleEntity loaded = fuzzy.findById(rule.getId()).orElseThrow();
        assertEquals("height is high", loaded.getCondition());
        assertEquals("set tall", loaded.getAction());
        assertFalse(loaded.isEnabled());
        assertEquals(1, fuzzy.findByProjectId("p1").size());
        assertEquals(0, fuzzy.findByProjectId("p2").size());
        fuzzy.deleteById(rule.getId());
        assertTrue(fuzzy.findById(rule.getId()).isEmpty());

        SparqlQueryEntity q = new SparqlQueryEntity();
        q.setProjectId("p1");
        q.setName("all");
        q.setQueryText("select * where { ?s ?p ?o }");
        queries.save(q);
        assertEquals("select * where { ?s ?p ?o }", queries.findById(q.getId()).orElseThrow().getQueryText());
        q.setName("renamed");
        queries.save(q);
        assertEquals(1, queries.findByProjectId("p1").size());
        assertEquals("renamed", queries.findByProjectId("p1").get(0).getName());
        queries.deleteById(q.getId());
        assertEquals(0, queries.findByProjectId("p1").size());
    }

    @Test
    void issueReportsKeepNestedInfoStatusAndRetentionDeletes() {
        IssueReport.SystemInfo info = IssueReport.SystemInfo.builder().osName("Linux").javaVersion("21").build();
        IssueReport fresh = issues.save(IssueReport.builder().title("t1").userEmail("a@x.org").reporterEmail("a@x.org")
                .projectId("p1").systemInfo(info).attachmentFileNames(List.of("a.png", "b.log"))
                .status(IssueReport.IssueStatus.SUBMITTED).createdAt(Instant.now()).build());
        IssueReport old = issues.save(IssueReport.builder().title("t2").userEmail("b@x.org").reporterEmail("c@x.org")
                .projectId("p1").status(IssueReport.IssueStatus.FAILED)
                .createdAt(Instant.now().minus(Duration.ofDays(400))).build());

        IssueReport loaded = issues.findById(fresh.getId()).orElseThrow();
        assertEquals("Linux", loaded.getSystemInfo().getOsName());
        assertEquals(List.of("a.png", "b.log"), loaded.getAttachmentFileNames());
        assertEquals(IssueReport.IssueStatus.SUBMITTED, loaded.getStatus());
        assertNull(issues.findById(old.getId()).orElseThrow().getSystemInfo());

        assertEquals(List.of(fresh.getId(), old.getId()),
                issues.findByProjectIdOrderByCreatedAtDesc("p1").stream().map(IssueReport::getId).toList());
        assertEquals(1, issues.findByUserEmailOrderByCreatedAtDesc("a@x.org").size());
        assertEquals(1, issues.findByStatusOrderByCreatedAtDesc(IssueReport.IssueStatus.FAILED).size());
        assertEquals(1, issues.deleteAllByCreatedAtBefore(Instant.now().minus(Duration.ofDays(365))));
        assertEquals(1, issues.deleteAllByUserEmailOrReporterEmail("zzz@x.org", "a@x.org"));
        assertTrue(issues.findByProjectIdOrderByCreatedAtDesc("p1").isEmpty());
    }

    @Test
    void importRecordsKeepTriplesAndDraftLookup() {
        SpreadsheetImportRecord r = new SpreadsheetImportRecord();
        r.setProjectId("p1");
        r.setUserId("u1");
        r.setUsername("ann");
        r.setFileName("a.xlsx");
        r.setDraft(true);
        r.setCreatedAt(Instant.now());
        r.setAxiomCount(3);
        r.setAddedTriples(List.of("<a> <b> <c> .", "<d> <e> \"f, g\" ."));
        imports.save(r);
        SpreadsheetImportRecord other = new SpreadsheetImportRecord();
        other.setProjectId("p1");
        other.setUserId("u2");
        other.setDraft(true);
        imports.save(other);

        List<SpreadsheetImportRecord> mine = imports.findByProjectIdAndUserIdAndDraftTrue("p1", "u1");
        assertEquals(1, mine.size());
        assertEquals(List.of("<a> <b> <c> .", "<d> <e> \"f, g\" ."), mine.get(0).getAddedTriples());
        assertEquals(3, mine.get(0).getAxiomCount());
        SpreadsheetImportRecord rolled = mine.get(0);
        rolled.setDraft(false);
        rolled.setRolledBack(true);
        rolled.setRolledBackAt(Instant.now());
        rolled.setRolledBackBy("ann");
        imports.save(rolled);
        assertTrue(imports.findByProjectIdAndUserIdAndDraftTrue("p1", "u1").isEmpty());
        assertTrue(imports.findById(rolled.getId()).orElseThrow().isRolledBack());
        imports.deleteById(rolled.getId());
        assertTrue(imports.findById(rolled.getId()).isEmpty());
    }

    @Test
    void ruleSetsStoreRulesAsJsonAndEnforceUniqueName() {
        SpreadsheetRuleSetEntity e = new SpreadsheetRuleSetEntity();
        e.setProjectId("p1");
        e.setName("main");
        e.setCreatedBy("ann");
        e.setRules(List.of(new RuleSpec("Sheet1", "A", "+", "2", "+", "Class: @A", "note", false),
                new RuleSpec("Sheet2", "B", "C", "1", "9", "Class: @B", null, null)));
        ruleSets.save(e);

        SpreadsheetRuleSetEntity loaded = ruleSets.findByProjectIdAndName("p1", "main").orElseThrow();
        assertEquals(2, loaded.getRules().size());
        assertEquals("Class: @A", loaded.getRules().get(0).rule());
        assertEquals(Boolean.FALSE, loaded.getRules().get(0).active());
        assertNull(loaded.getRules().get(1).active());

        SpreadsheetRuleSetEntity dup = new SpreadsheetRuleSetEntity();
        dup.setProjectId("p1");
        dup.setName("main");
        assertThrows(DuplicateKeyException.class, () -> ruleSets.save(dup));
        dup.setProjectId("p2");
        ruleSets.save(dup);

        loaded.setUpdatedAt(new Date(System.currentTimeMillis() + 5000));
        ruleSets.save(loaded);
        assertEquals(1, ruleSets.findByProjectIdOrderByUpdatedAtDesc("p1").size());
        ruleSets.delete(loaded);
        assertTrue(ruleSets.findById(loaded.getId()).isEmpty());
    }

    @Test
    void swrlRulesShareTheSwrlServiceTableWithoutTouchingItsColumns() {
        jdbc.update("insert into swrl_rule (id, project_id, rule_name, rule_text, execution_count, validated) "
                + "values ('r1', 'p1', 'n1', 'A(?x) -> B(?x)', 7, true)");
        SwrlRuleEntity e = swrl.findById("r1").orElseThrow();
        assertEquals("A(?x) -> B(?x)", e.getRuleText());
        e.setEnabled(false);
        e.setComment("c");
        swrl.save(e);
        assertEquals(7, jdbc.queryForObject("select execution_count from swrl_rule where id = 'r1'", Integer.class));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("select validated from swrl_rule where id = 'r1'", Boolean.class));
        assertFalse(swrl.findById("r1").orElseThrow().isEnabled());

        SwrlRuleEntity second = new SwrlRuleEntity();
        second.setProjectId("p1");
        second.setRuleName("n2");
        second.setRuleText("C(?x) -> D(?x)");
        second.setCreatedAt(new Date(System.currentTimeMillis() + 1000));
        swrl.save(second);
        assertNotNull(second.getId());
        assertEquals(second.getId(), swrl.findByProjectIdOrderByCreatedAtDesc("p1").get(0).getId());
        assertThrows(DuplicateKeyException.class, () -> {
            SwrlRuleEntity clash = new SwrlRuleEntity();
            clash.setProjectId("p1");
            clash.setRuleName("n2");
            swrl.save(clash);
        });
        swrl.deleteById("r1");
        assertTrue(swrl.findById("r1").isEmpty());
    }

    @Test
    void projectTablesAreRegisteredForPurge() {
        assertTrue(ProjectScopedTables.names().containsAll(List.of("datatype_definition", "fuzzy_rule", "sparql_query",
                "spreadsheet_import_record", "spreadsheet_rule_set", "swrl_rule")));
    }
}
