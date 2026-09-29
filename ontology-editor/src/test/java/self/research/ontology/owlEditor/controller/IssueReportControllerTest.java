package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.model.IssueReport;
import self.research.ontology.owlEditor.service.IssueReportService;
import self.research.ontology.owlEditor.service.OpenProjectService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class IssueReportControllerTest {

    @Mock
    private IssueReportService issueReportService;

    private IssueReportController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new IssueReportController(issueReportService);
    }

    @Test
    void reportIssueReturnsIssueKeyAndUrlOnSuccess() {
        when(issueReportService.submitIssueReport(any(), any())).thenReturn(
                IssueReportService.IssueReportResult.builder()
                        .success(true)
                        .message("Bug reported successfully")
                        .issueReportId("issue-1")
                        .issueKey("42")
                        .issueUrl("https://openproject.test/work_packages/42")
                        .build());

        ResponseEntity<Map<String, Object>> response = controller.reportIssue(
                new MockHttpServletRequest(), "title", "description", null, null, null, null,
                null, null, null, null, null, null, "Bug", null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals("42", body.get("issueKey"));
        assertEquals("https://openproject.test/work_packages/42", body.get("issueUrl"));
        assertFalse(body.containsKey("jiraIssueKey"));
        assertFalse(body.containsKey("jiraIssueUrl"));
    }

    @Test
    void reportIssueReturnsTrackerFailureReasonWhenTrackerFailedButSavedLocally() {
        when(issueReportService.submitIssueReport(any(), any())).thenReturn(
                IssueReportService.IssueReportResult.builder()
                        .success(true)
                        .message("Issue saved locally but failed to create OpenProject ticket. Our team has been notified.")
                        .issueReportId("issue-1")
                        .trackerFailureReason("OpenProject rejected the API token (401)")
                        .build());

        ResponseEntity<Map<String, Object>> response = controller.reportIssue(
                new MockHttpServletRequest(), "title", "description", null, null, null, null,
                null, null, null, null, null, null, "Bug", null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("OpenProject rejected the API token (401)", response.getBody().get("trackerFailureReason"));
        assertFalse(response.getBody().containsKey("jiraFailureReason"));
    }

    @Test
    void reportIssueReturnsBadRequestWhenValidationFails() {
        when(issueReportService.submitIssueReport(any(), any())).thenReturn(
                IssueReportService.IssueReportResult.builder()
                        .success(false)
                        .message("Title is required")
                        .build());

        ResponseEntity<Map<String, Object>> response = controller.reportIssue(
                new MockHttpServletRequest(), "", "description", null, null, null, null,
                null, null, null, null, null, null, "Bug", null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void getUserIssueReportsReturnsRepositoryResults() {
        when(issueReportService.getUserIssueReports("a@b.com"))
                .thenReturn(List.of(IssueReport.builder().id("issue-1").build()));

        ResponseEntity<List<IssueReport>> response = controller.getUserIssueReports("a@b.com");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void validateOpenProjectConnectionReturnsProjectName() {
        when(issueReportService.validateOpenProjectConnection()).thenReturn(
                OpenProjectService.OpenProjectValidationResult.builder()
                        .success(true)
                        .message("Successfully connected to OpenProject project: Demo")
                        .projectName("Demo")
                        .build());

        ResponseEntity<Map<String, Object>> response = controller.validateOpenProjectConnection();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) response.getBody().get("success"));
        assertEquals("Demo", response.getBody().get("projectName"));
    }
}
