package self.research.ontology.owlEditor.repository;

import self.research.ontology.owlEditor.model.IssueReport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface IssueReportRepository {

    IssueReport save(IssueReport report);

    Optional<IssueReport> findById(String id);

    List<IssueReport> findByUserEmailOrderByCreatedAtDesc(String userEmail);

    List<IssueReport> findByProjectIdOrderByCreatedAtDesc(String projectId);

    List<IssueReport> findByStatusOrderByCreatedAtDesc(IssueReport.IssueStatus status);

    long deleteAllByCreatedAtBefore(Instant cutoff);

    long deleteAllByUserEmailOrReporterEmail(String userEmail, String reporterEmail);
}
