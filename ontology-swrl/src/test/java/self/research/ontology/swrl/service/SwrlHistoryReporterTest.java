package self.research.ontology.swrl.service;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.swrl.model.SwrlRule;
import self.research.ontology.swrl.repository.SwrlRuleRepository;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SwrlHistoryReporterTest {

    private static final String ID_A = "6acf64ae94e5320b04bb880a";
    private static final String ID_B = "6acf64ae94e5320b04bb880b";

    private SwrlRuleRepository repository;
    private RestTemplate restTemplate;
    private SwrlHistoryReporter reporter;

    @BeforeEach
    void setUp() {
        repository = mock(SwrlRuleRepository.class);
        restTemplate = mock(RestTemplate.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MongoConverter converter = mock(MongoConverter.class);
        when(mongoTemplate.getConverter()).thenReturn(converter);
        doAnswer(invocation -> {
            SwrlRule rule = invocation.getArgument(0);
            Document document = (Document) invocation.getArgument(1);
            document.put("_id", new ObjectId(rule.getId()));
            document.put("projectId", rule.getProjectId());
            document.put("ruleName", rule.getRuleName());
            document.put("ruleText", rule.getRuleText());
            document.put("executionCount", rule.getExecutionCount());
            return null;
        }).when(converter).write(any(Object.class), any(org.bson.conversions.Bson.class));
        reporter = new SwrlHistoryReporter(repository, mongoTemplate, "http://editor", restTemplate);
    }

    private static SwrlRule rule(String id, String text, int runs) {
        SwrlRule rule = new SwrlRule();
        rule.setId(id);
        rule.setProjectId("p1");
        rule.setRuleName("rule-" + id.charAt(id.length() - 1));
        rule.setRuleText(text);
        rule.setExecutionCount(runs);
        return rule;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sent(int expected) {
        ArgumentCaptor<HttpEntity<Map<String, Object>>> bodies = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(expected)).postForEntity(eq("http://editor/api/ontology/p1/changes/document"),
                bodies.capture(), eq(String.class));
        return bodies.getAllValues().stream().map(HttpEntity::getBody).toList();
    }

    @Test
    void aNewRuleIsReportedAsACreation() {
        when(repository.findByProjectId("p1")).thenReturn(List.of(), List.of(rule(ID_A, "A(?x) -> B(?x)", 0)));

        reporter.around("p1", "Bearer t", () -> "created");

        Map<String, Object> body = sent(1).get(0);
        assertEquals("createSwrlRule", body.get("operationType"));
        assertNull(body.get("before"));
        assertTrue(((String) body.get("after")).contains("A(?x) -> B(?x)"));
        assertEquals("swrlRule", body.get("store"));
    }

    @Test
    void aChangedRuleIsReportedWithItsBeforeAndAfter() {
        when(repository.findByProjectId("p1")).thenReturn(List.of(rule(ID_A, "old", 0)), List.of(rule(ID_A, "new", 0)));

        reporter.around("p1", "Bearer t", () -> null);

        Map<String, Object> body = sent(1).get(0);
        assertEquals("updateSwrlRule", body.get("operationType"));
        assertEquals("old", body.get("oldText"));
        assertEquals("new", body.get("newText"));
    }

    @Test
    void aDeletedRuleIsReportedWithItsLastState() {
        when(repository.findByProjectId("p1")).thenReturn(List.of(rule(ID_A, "gone", 0)), List.of());

        reporter.around("p1", null, () -> null);

        Map<String, Object> body = sent(1).get(0);
        assertEquals("deleteSwrlRule", body.get("operationType"));
        assertNull(body.get("after"));
    }

    @Test
    void runningARuleIsNotReportedAsAnEdit() {
        when(repository.findByProjectId("p1")).thenReturn(List.of(rule(ID_A, "same", 1)), List.of(rule(ID_A, "same", 5)));

        reporter.around("p1", null, () -> null);

        verify(restTemplate, never()).postForEntity(anyString(), any(), any(Class.class));
    }

    @Test
    void aRequestThatDidNotSucceedIsNeverReported() throws Exception {
        when(repository.findByProjectId("p1")).thenReturn(List.of(rule(ID_A, "x", 0)));

        reporter.aroundRequest("p1", "Bearer t", () -> false);

        verify(restTemplate, never()).postForEntity(anyString(), any(), any(Class.class));
    }

    @Test
    void aBatchIsReportedRuleByRuleAndAnEditorOutageNeverFailsTheRuleWrite() {
        when(repository.findByProjectId("p1")).thenReturn(List.of(),
                List.of(rule(ID_A, "one", 0), rule(ID_B, "two", 0)));
        when(restTemplate.postForEntity(anyString(), any(), any(Class.class))).thenThrow(new IllegalStateException("down"));

        String result = reporter.around("p1", "Bearer t", () -> "ok");

        assertEquals("ok", result);
        sent(2);
    }
}
