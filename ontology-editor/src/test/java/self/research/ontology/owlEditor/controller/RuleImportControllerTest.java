package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.ChangeOrigin;
import self.research.ontology.owlEditor.service.RuleImportReport;
import self.research.ontology.owlEditor.service.RuleImporter;
import self.research.ontology.owlEditor.service.RuleService;
import self.research.ontology.owlEditor.service.RuleWriteAccess;
import self.research.ontology.owlEditor.service.SwrlRuleClient;
import self.research.ontology.owlEditor.service.spreadsheet.RuleImportFile;
import self.research.ontology.owlEditor.service.spreadsheet.RuleSpreadsheet;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleImportControllerTest {

    RuleService service;
    RuleImportController controller;

    static final byte[] JSON = "[{\"ruleName\":\"A\",\"ruleText\":\"X>Y\"}]".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        service = mock(RuleService.class);
        controller = new RuleImportController(service);
    }

    ResponseEntity<?> post(MockMultipartFile file, boolean dry) {
        return controller.importRules("p", false, dry, file, "11111111-1111-1111-1111-111111111111", null, "u", "U",
                new MockHttpServletRequest());
    }

    MockMultipartFile json() {
        return new MockMultipartFile("file", "rules.json", "application/json", JSON);
    }

    RuleImportReport report() {
        return new RuleImportReport(1, List.of(new RuleImportReport.Imported("id", "A", 1)), List.of(), 0, 0, false, null,
                false, "cs1", false, null);
    }

    void serviceReturns(Object outcome) throws Exception {
        var call = when(service.importRules(anyString(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(),
                any(ChangeOrigin.class), any()));
        if (outcome instanceof RuntimeException failure) {
            call.thenThrow(failure);
        } else {
            call.thenReturn((RuleImportReport) outcome);
        }
    }

    @Test
    void aGoodImportAnswers200WithTheReport() throws Exception {
        serviceReturns(report());

        ResponseEntity<?> response = post(json(), false);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("cs1", ((RuleImportReport) response.getBody()).changeSetId());
    }

    @Test
    void aStaleStampAnswers409WithTheFreshReport() throws Exception {
        serviceReturns(new RuleService.ImportStaleException(report().asStale("2 rows are now duplicates")));

        ResponseEntity<?> response = post(json(), false);

        assertEquals(409, response.getStatusCode().value());
        RuleImportReport body = (RuleImportReport) response.getBody();
        assertTrue(body.stale());
        assertEquals("2 rows are now duplicates", body.message());
    }

    @Test
    void theOtherRefusalsHaveTheirOwnStatusAndAMessage() throws Exception {
        Object[][] cases = {
                {new RuleService.ImportConflictException("different file"), 409},
                {new RuleService.ImportForbiddenException("view only"), 403},
                {new RuleService.ImportFailedException("could not save"), 500},
                {new IllegalArgumentException("needs an importId"), 400},
                {new SwrlRuleClient.UnavailableException("down"), 503}};
        for (Object[] item : cases) {
            serviceReturns(item[0]);

            ResponseEntity<?> response = post(json(), false);

            assertEquals(item[1], response.getStatusCode().value(), String.valueOf(item[0]));
            assertEquals(((RuntimeException) item[0]).getMessage(), ((Map<?, ?>) response.getBody()).get("message"));
        }
    }

    @Test
    void anUnreadableFileAnswers400AndAnOversizeOneAnswers413() {
        ResponseEntity<?> bad = post(new MockMultipartFile("file", "rules.xlsx", null, "nope".getBytes(StandardCharsets.UTF_8)), false);
        ResponseEntity<?> big = post(new MockMultipartFile("file", "rules.xlsx", null, new byte[RuleSpreadsheet.MAX_FILE_BYTES + 1]), false);
        ResponseEntity<?> empty = post(new MockMultipartFile("file", "rules.xlsx", null, new byte[0]), false);

        assertEquals(400, bad.getStatusCode().value());
        assertEquals("This is not a valid .xlsx file", ((Map<?, ?>) bad.getBody()).get("message"));
        assertEquals(413, big.getStatusCode().value());
        assertEquals(400, empty.getStatusCode().value());
    }

    @Test
    void aDryRunPassesTheFlagAndThePermissionIsCheckedAgainstTheProjectRules() throws Exception {
        RuleWriteAccess access = mock(RuleWriteAccess.class);
        when(access.decide(any(), any(), anyString())).thenReturn(new RuleWriteAccess.Decision(false, "view only"));
        ReflectionTestUtils.setField(controller, "writeAccess", access);
        when(service.importRules(anyString(), anyBoolean(), any(), any(), any(), any(), any(), anyBoolean(),
                any(ChangeOrigin.class), any())).thenAnswer(inv -> {
                    Supplier<String> permission = inv.getArgument(9);
                    String refusal = permission.get();
                    if (refusal != null) {
                        throw new RuleService.ImportForbiddenException(refusal);
                    }
                    return report();
                });

        ResponseEntity<?> response = post(json(), true);

        assertEquals(403, response.getStatusCode().value());
        verify(service).importRules(anyString(), anyBoolean(), any(), any(), any(RuleImportFile.class), any(), any(),
                eq(true), any(ChangeOrigin.class), any());
    }

    @Test
    void exportAnswersTheFileWithItsLeftOutCountAndTheTemplateIsAnXlsx() throws Exception {
        when(service.exportRules("p", false, null)).thenReturn(new RuleImporter.Export(
                List.of(new RuleSpreadsheet.ExportRule("A", "X>Y", "", "", true)), Map.of(), 2));

        ResponseEntity<?> xlsx = controller.export("p", "xlsx", false, null, new MockHttpServletRequest());
        ResponseEntity<?> json = controller.export("p", "json", false, null, new MockHttpServletRequest());
        ResponseEntity<?> wrong = controller.export("p", "csv", false, null, new MockHttpServletRequest());

        assertEquals(200, xlsx.getStatusCode().value());
        assertEquals("2", xlsx.getHeaders().getFirst("X-Rules-Left-Out"));
        assertTrue(xlsx.getHeaders().getFirst("Content-Disposition").endsWith(".xlsx\""));
        assertTrue(new String((byte[]) json.getBody(), StandardCharsets.UTF_8).contains("\"ruleName\""));
        assertEquals(400, wrong.getStatusCode().value());
        assertEquals(200, controller.template("p").getStatusCode().value());
        assertTrue(controller.template("p").getBody().length > 1000);
    }
}
