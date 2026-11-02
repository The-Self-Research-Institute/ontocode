package self.research.ontology.owlEditor.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import self.research.ontology.owlEditor.model.DLQueryJob;
import self.research.ontology.owlEditor.model.ExportJob;

import java.util.Optional;

@Component
public class JobProjectResolver {

    private static final AntPathMatcher PATH = new AntPathMatcher();
    private static final String DL_QUERY_JOB = "/api/dl-query/jobs/{jobId}";
    private static final String REASONER_JOB = "/api/ontology/{projectId}/reasoner/jobs/{jobId}";
    private static final String EXPORT_JOB = "/api/ontology/export-async/{action}/{jobId}";

    private final DLQueryQueueManager queueManager;
    private final ObjectProvider<ReasoningJobRelayService> relayService;
    private final OntologyExportJobService exportJobService;

    public JobProjectResolver(DLQueryQueueManager queueManager, ObjectProvider<ReasoningJobRelayService> relayService,
                              OntologyExportJobService exportJobService) {
        this.queueManager = queueManager;
        this.relayService = relayService;
        this.exportJobService = exportJobService;
    }

    public Optional<String> projectIdOf(String path) {
        if (PATH.match(DL_QUERY_JOB, path)) {
            return dlQueryProject(PATH.extractUriTemplateVariables(DL_QUERY_JOB, path).get("jobId"));
        }
        if (PATH.match(REASONER_JOB, path)) {
            return dlQueryProject(PATH.extractUriTemplateVariables(REASONER_JOB, path).get("jobId"));
        }
        if (PATH.match(EXPORT_JOB, path)) {
            return exportJobService.getStatus(PATH.extractUriTemplateVariables(EXPORT_JOB, path).get("jobId"))
                    .map(ExportJob::getProjectId);
        }
        return Optional.empty();
    }

    private Optional<String> dlQueryProject(String jobId) {
        DLQueryJob job = queueManager.getJob(jobId);
        if (job == null) {
            ReasoningJobRelayService relay = relayService.getIfAvailable();
            job = relay == null ? null : relay.getJob(jobId);
        }
        return Optional.ofNullable(job).map(DLQueryJob::getProjectId);
    }
}
