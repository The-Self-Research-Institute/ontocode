package self.research.ontology.owlEditor.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.repository.IssueReportRepository;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserDataController {

    private final IssueReportRepository issueReportRepository;

    @DeleteMapping("/{email}/issue-reports")
    public ResponseEntity<Map<String, Object>> deleteIssueReportsForUser(@PathVariable String email) {
        long deleted = issueReportRepository.deleteAllByUserEmailOrReporterEmail(email, email);
        log.info("Deleted {} issue report(s) for user {} (account deletion)", deleted, email);
        return ResponseEntity.ok(Map.of("success", true, "deleted", deleted));
    }
}
