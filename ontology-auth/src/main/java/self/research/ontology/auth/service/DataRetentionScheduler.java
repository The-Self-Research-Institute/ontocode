package self.research.ontology.auth.service;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.FileMetadata;
import self.research.ontology.auth.model.Project;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.FileMetadataRepository;
import self.research.ontology.auth.repository.ProjectRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DataRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionScheduler.class);

    private final FileMetadataRepository fileMetadataRepository;
    private final ProjectRepository projectRepository;
    private final WorkspaceRepository workspaceRepository;
    private final GridFsTemplate gridFsTemplate;
    private final ProjectService projectService;
    private final WorkspaceService workspaceService;

    @Value("${data.retention.purge.enabled:true}")
    private boolean purgeEnabled;

    @Value("${data.retention.purge.days:30}")
    private int retentionDays;

    public DataRetentionScheduler(FileMetadataRepository fileMetadataRepository,
                                   ProjectRepository projectRepository,
                                   WorkspaceRepository workspaceRepository,
                                   GridFsTemplate gridFsTemplate,
                                   ProjectService projectService,
                                   WorkspaceService workspaceService) {
        this.fileMetadataRepository = fileMetadataRepository;
        this.projectRepository = projectRepository;
        this.workspaceRepository = workspaceRepository;
        this.gridFsTemplate = gridFsTemplate;
        this.projectService = projectService;
        this.workspaceService = workspaceService;
    }

    @Scheduled(cron = "${data.retention.purge.cron:0 0 3 * * ?}")
    public void purgeSoftDeletedData() {
        if (!purgeEnabled) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        List<FileMetadata> filesToPurge = fileMetadataRepository.findAllByIsDeletedTrueAndDeletedAtBefore(cutoff);
        for (FileMetadata file : filesToPurge) {
            if (file.getGridfsId() != null) {
                try {
                    gridFsTemplate.delete(Query.query(Criteria.where("_id").is(new ObjectId(file.getGridfsId()))));
                } catch (Exception e) {
                    log.warn("Could not delete GridFS object {} during retention purge: {}", file.getGridfsId(), e.getMessage());
                }
            }
        }
        fileMetadataRepository.deleteAll(filesToPurge);

        List<Project> projectsToPurge = projectRepository.findAllByIsDeletedTrueAndDeletedAtBefore(cutoff);
        for (Project project : projectsToPurge) {
            try {
                projectService.purgeProjectData(project);
            } catch (Exception e) {
                log.warn("Could not purge project {} during retention purge: {}", project.getProjectId(), e.getMessage());
            }
        }

        List<Workspace> workspacesToPurge = workspaceRepository.findAllByIsDeletedTrueAndDeletedAtBefore(cutoff);
        for (Workspace workspace : workspacesToPurge) {
            try {
                workspaceService.purgeWorkspaceData(workspace);
            } catch (Exception e) {
                log.warn("Could not purge workspace {} during retention purge: {}", workspace.getWorkspaceId(), e.getMessage());
            }
        }

        log.info("Data retention purge complete: {} files, {} projects, {} workspaces removed (older than {} days)",
                filesToPurge.size(), projectsToPurge.size(), workspacesToPurge.size(), retentionDays);
    }
}
