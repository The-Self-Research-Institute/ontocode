package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.model.IssueReport;
import self.research.ontology.owlEditor.repository.IssueReportRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueReportServiceTest {

    @Mock
    private IssueReportRepository issueReportRepository;

    @Mock
    private OpenProjectService openProjectService;

    private IssueReportService issueReportService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        issueReportService = new IssueReportService(issueReportRepository, openProjectService);
        when(issueReportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private IssueReport.IssueReportBuilder validReport() {
        return IssueReport.builder().id("issue-1").title("Something broke").description("It broke badly");
    }

    @Test
    void rejectsMissingTitle() {
        IssueReportService.IssueReportResult result = issueReportService.submitIssueReport(
                IssueReport.builder().description("desc").build(), null);

        assertFalse(result.isSuccess());
        assertEquals("Title is required", result.getMessage());
    }

    @Test
    void rejectsMissingDescription() {
        IssueReportService.IssueReportResult result = issueReportService.submitIssueReport(
                IssueReport.builder().title("title").build(), null);

        assertFalse(result.isSuccess());
        assertEquals("Description is required", result.getMessage());
    }

    @Test
    void savesLocallyWhenOpenProjectDisabled() {
        when(openProjectService.isEnabled()).thenReturn(false);

        IssueReportService.IssueReportResult result = issueReportService.submitIssueReport(validReport().build(), null);

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("logged locally"));
        assertNull(result.getIssueKey());
        verify(openProjectService, never()).createIssue(any(), any(), any(), any());
    }

    @Test
    void submitsToOpenProjectAndUploadsAttachmentsOnSuccess() {
        when(openProjectService.isEnabled()).thenReturn(true);
        when(openProjectService.createIssue(anyString(), anyString(), any(), anyString()))
                .thenReturn(OpenProjectService.OpenProjectIssueResult.builder()
                        .success(true).issueKey("42").issueUrl("https://openproject.test/work_packages/42").build());
        when(openProjectService.uploadAttachment(eq("42"), anyString(), any())).thenReturn(true);

        org.springframework.mock.web.MockMultipartFile attachment = new org.springframework.mock.web.MockMultipartFile(
                "attachments", "log.txt", "text/plain", "log contents".getBytes());

        IssueReportService.IssueReportResult result = issueReportService.submitIssueReport(
                validReport().issueType("Bug").build(), List.of(attachment));

        assertTrue(result.isSuccess());
        assertEquals("42", result.getIssueKey());
        assertEquals("https://openproject.test/work_packages/42", result.getIssueUrl());
        assertEquals("Bug reported successfully", result.getMessage());
        assertNull(result.getTrackerFailureReason());
        verify(openProjectService, times(1)).uploadAttachment(eq("42"), eq("log.txt"), any());
    }

    @Test
    void savesLocallyWithFailureReasonWhenOpenProjectRejectsIssue() {
        when(openProjectService.isEnabled()).thenReturn(true);
        when(openProjectService.createIssue(anyString(), anyString(), any(), anyString()))
                .thenReturn(OpenProjectService.OpenProjectIssueResult.builder()
                        .success(false).errorMessage("OpenProject rejected the API token (401)").build());

        IssueReportService.IssueReportResult result = issueReportService.submitIssueReport(validReport().build(), null);

        assertTrue(result.isSuccess());
        assertEquals("OpenProject rejected the API token (401)", result.getTrackerFailureReason());
        assertNull(result.getIssueKey());
    }

    @Test
    void savesLocallyWithFailureReasonWhenOpenProjectThrows() {
        when(openProjectService.isEnabled()).thenReturn(true);
        when(openProjectService.createIssue(anyString(), anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("connection refused"));

        IssueReportService.IssueReportResult result = issueReportService.submitIssueReport(validReport().build(), null);

        assertTrue(result.isSuccess());
        assertTrue(result.getTrackerFailureReason().contains("connection refused"));
    }

    @Test
    void getUserIssueReportsDelegatesToRepository() {
        when(issueReportRepository.findByUserEmailOrderByCreatedAtDesc("a@b.com"))
                .thenReturn(List.of(validReport().build()));

        List<IssueReport> reports = issueReportService.getUserIssueReports("a@b.com");

        assertEquals(1, reports.size());
    }

    @Test
    void validateOpenProjectConnectionDelegatesToService() {
        when(openProjectService.validateConnection())
                .thenReturn(OpenProjectService.OpenProjectValidationResult.builder().success(true).build());

        assertTrue(issueReportService.validateOpenProjectConnection().isSuccess());
    }
}
