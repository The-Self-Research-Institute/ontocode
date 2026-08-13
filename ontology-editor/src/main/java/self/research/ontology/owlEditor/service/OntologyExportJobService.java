package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.ExportJob;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class OntologyExportJobService {

    private static final Duration STUCK_JOB_TIMEOUT = Duration.ofMinutes(45);
    private static final Duration FINISHED_JOB_RETENTION = Duration.ofHours(1);

    private final Executor owlParsingExecutor;
    private final StorageManager storageManager;
    private final ProjectImportService importService;

    private final Map<String, ExportJob> jobs = new ConcurrentHashMap<>();

    private final Map<String, String> activeJobByKey = new ConcurrentHashMap<>();

    public OntologyExportJobService(@Qualifier("owlParsingExecutor") Executor owlParsingExecutor,
                                     StorageManager storageManager,
                                     ProjectImportService importService) {
        this.owlParsingExecutor = owlParsingExecutor;
        this.storageManager = storageManager;
        this.importService = importService;
    }

    public ExportJob submit(String projectId, String format) {
        String key = dedupKey(projectId, format);
        String jobId = UUID.randomUUID().toString();
        ExportJob newJob = ExportJob.builder()
                .jobId(jobId)
                .projectId(projectId)
                .format(format)
                .status(ExportJob.Status.PENDING)
                .createdAt(Instant.now())
                .build();

        String[] resolvedJobId = new String[1];
        activeJobByKey.compute(key, (k, existingJobId) -> {
            if (existingJobId != null) {
                ExportJob existing = jobs.get(existingJobId);
                if (existing != null && (existing.getStatus() == ExportJob.Status.PENDING
                        || existing.getStatus() == ExportJob.Status.PROCESSING)) {
                    resolvedJobId[0] = existingJobId;
                    return existingJobId;
                }
            }
            jobs.put(jobId, newJob);
            resolvedJobId[0] = jobId;
            return jobId;
        });

        if (jobId.equals(resolvedJobId[0])) {
            owlParsingExecutor.execute(() -> runExport(jobId));
            log.info("[Export] Submitted export job {} for project {} ({})", jobId, projectId, format);
            return newJob;
        }
        log.info("[Export] Reusing in-flight export job {} for {}", resolvedJobId[0], key);
        return jobs.get(resolvedJobId[0]);
    }

    public Optional<ExportJob> getStatus(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    private void runExport(String jobId) {
        ExportJob job = jobs.get(jobId);
        if (job == null) {
            return;
        }
        jobs.put(jobId, job.toBuilder().status(ExportJob.Status.PROCESSING).build());

        try {
            Path exportPath;

            importService.syncProjectToFuseki(job.getProjectId());
            Optional<String> cachedContent = storageManager.getCodeViewCache(job.getProjectId(), job.getFormat());
            if (cachedContent.isPresent()) {
                log.info("[Export] Job {} using cached code view content (project {}, format {})",
                        jobId, job.getProjectId(), job.getFormat());
                String extension = storageManager.extensionFor(job.getFormat());
                exportPath = storageManager.projectDir(job.getProjectId()).resolve("ontology.export." + extension);
                Files.createDirectories(exportPath.getParent());
                Files.writeString(exportPath, cachedContent.get());
            } else {
                exportPath = storageManager.exportOntologyForJob(job.getProjectId(), job.getFormat());
            }

            ExportJob current = jobs.get(jobId);
            if (current == null) {
                return;
            }
            jobs.put(jobId, current.toBuilder()
                    .status(ExportJob.Status.COMPLETED)
                    .resultPath(exportPath)
                    .completedAt(Instant.now())
                    .build());
            log.info("[Export] Job {} completed", jobId);
        } catch (Exception e) {
            log.error("[Export] Job {} failed", jobId, e);
            ExportJob current = jobs.get(jobId);
            if (current != null) {
                jobs.put(jobId, current.toBuilder()
                        .status(ExportJob.Status.ERROR)
                        .error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                        .completedAt(Instant.now())
                        .build());
            }
        } finally {
            activeJobByKey.remove(dedupKey(job.getProjectId(), job.getFormat()), jobId);
        }
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void sweep() {
        Instant now = Instant.now();
        for (Map.Entry<String, ExportJob> entry : jobs.entrySet()) {
            ExportJob job = entry.getValue();
            if (job.getStatus() == ExportJob.Status.PROCESSING
                    && job.getCreatedAt() != null
                    && Duration.between(job.getCreatedAt(), now).compareTo(STUCK_JOB_TIMEOUT) > 0) {
                log.warn("[Export] Job {} exceeded {} — marking as timed out", entry.getKey(), STUCK_JOB_TIMEOUT);
                jobs.put(entry.getKey(), job.toBuilder()
                        .status(ExportJob.Status.ERROR)
                        .error("Export timed out after " + STUCK_JOB_TIMEOUT.toMinutes() + " minutes")
                        .completedAt(now)
                        .build());
                activeJobByKey.remove(dedupKey(job.getProjectId(), job.getFormat()), entry.getKey());
            }
        }
        jobs.entrySet().removeIf(entry -> {
            ExportJob job = entry.getValue();
            boolean finished = job.getStatus() == ExportJob.Status.COMPLETED || job.getStatus() == ExportJob.Status.ERROR;
            return finished && job.getCompletedAt() != null
                    && Duration.between(job.getCompletedAt(), now).compareTo(FINISHED_JOB_RETENTION) > 0;

        });
    }

    private String dedupKey(String projectId, String format) {
        return projectId + "::" + (format == null ? "rdfxml" : format.toLowerCase());
    }
}
