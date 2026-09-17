package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.repository.IssueReportRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Purges issue reports (support requests / bug reports) older than the configured
 * retention window, matching the storage-limitation policy described in the privacy
 * policy. Mirrors DataRetentionScheduler (ontology-auth) for files/projects/workspaces.
 *
 * This is the automatic backstop for users who never request deletion themselves —
 * proactive erasure requests are still handled separately via privacy@ontocode.org
 * (see scripts/delete-user-complete.js), which already deletes a user's issue_reports.
 */
@Slf4j
@Service
public class IssueReportRetentionScheduler {

    private final IssueReportRepository issueReportRepository;

    @Value("${data.retention.issue-reports.enabled:true}")
    private boolean purgeEnabled;

    @Value("${data.retention.issue-reports.days:730}")
    private int retentionDays;

    public IssueReportRetentionScheduler(IssueReportRepository issueReportRepository) {
        this.issueReportRepository = issueReportRepository;
    }

    @Scheduled(cron = "${data.retention.issue-reports.cron:0 15 3 * * ?}")
    public void purgeExpiredIssueReports() {
        if (!purgeEnabled) {
            return;
        }

        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        long deleted = issueReportRepository.deleteAllByCreatedAtBefore(cutoff);

        log.info("Issue report retention purge complete: {} report(s) removed (older than {} days)",
                deleted, retentionDays);
    }
}
