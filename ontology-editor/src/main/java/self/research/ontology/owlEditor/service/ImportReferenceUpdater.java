package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.util.SparqlSafety;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class ImportReferenceUpdater {

    private static final String PROJECT_GRAPH_PREFIX = "http://ontocode.org/project/";
    private static final String OWL_IMPORTS = "http://www.w3.org/2002/07/owl#imports";

    public record Actor(String userId, String email, String username) {}

    public record Updated(String projectId, String name) {}

    public record Skipped(String projectId, String name, String owner, String reason) {}

    public record Result(List<Updated> updated, List<Skipped> skipped) {
        public static Result none() {
            return new Result(List.of(), List.of());
        }
    }

    private final SparqlDatasetService datasetService;
    private final ProjectRepository projectRepository;
    private final MongoTemplate mongoTemplate;
    private final WorkspaceOwnershipService ownership;
    private final OntologyMutationService mutationService;
    private final ProjectWriteLockRegistry locks;
    private final boolean desktopMode;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OperationGroups groups;

    public ImportReferenceUpdater(SparqlDatasetService datasetService, ProjectRepository projectRepository,
                                  MongoTemplate mongoTemplate, WorkspaceOwnershipService ownership,
                                  OntologyMutationService mutationService, ProjectWriteLockRegistry locks,
                                  @Value("${ontocode.desktop.mode:false}") boolean desktopMode) {
        this.datasetService = datasetService;
        this.projectRepository = projectRepository;
        this.mongoTemplate = mongoTemplate;
        this.ownership = ownership;
        this.mutationService = mutationService;
        this.locks = locks;
        this.desktopMode = desktopMode;
    }

    public Result update(String sourceProjectId, String oldIri, String newIri, Actor actor, String groupId) {
        if (blank(oldIri) || blank(newIri) || oldIri.equals(newIri)) {
            return Result.none();
        }
        String safeOld;
        String safeNew;
        try {
            safeOld = SparqlSafety.safeIri(oldIri);
            safeNew = SparqlSafety.safeIri(newIri);
        } catch (IllegalArgumentException e) {
            return Result.none();
        }
        List<Updated> updated = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (String projectId : importersOf(sourceProjectId, safeOld)) {
            String name = projectRepository.findById(projectId).map(ProjectDocument::getName).orElse(projectId);
            String owner = projectRepository.findById(projectId).map(ProjectDocument::getOwnerEmail).orElse(null);
            if (!mayEdit(actor, projectId)) {
                skipped.add(new Skipped(projectId, name, owner, "You don't have edit rights in this project"));
                continue;
            }
            try {
                String sparql = "PREFIX owl: <http://www.w3.org/2002/07/owl#>\n"
                        + "DELETE { ?o owl:imports <" + safeOld + "> } INSERT { ?o owl:imports <" + safeNew + "> } "
                        + "WHERE { ?o owl:imports <" + safeOld + "> }";
                java.time.LocalDateTime since = java.time.LocalDateTime.now().minusSeconds(1);
                locks.runExclusive(projectId, () -> {
                    mutationService.applyRawUpdateWithHistory(projectId, sparql, false, actor.email(), actor.username());
                    if (groups != null && groupId != null) {
                        groups.tag(projectId, actor.email(), since, groupId);
                    }
                    return null;
                });
                updated.add(new Updated(projectId, name));
            } catch (Exception e) {
                log.warn("[IMPORTS] Could not update the import of {} in project {}: {}", oldIri, projectId, e.getMessage());
                skipped.add(new Skipped(projectId, name, owner, "The update failed"));
            }
        }
        return new Result(updated, skipped);
    }

    private boolean mayEdit(Actor actor, String projectId) {
        if (desktopMode) {
            return true;
        }
        if (actor == null) {
            return false;
        }
        if (!blank(actor.userId()) && ownership.canPublishToProject(actor.userId(), projectId)) {
            return true;
        }
        return !blank(actor.email()) && projectRepository.findById(projectId).map(ProjectDocument::getOwnerEmail)
                .filter(owner -> owner.equalsIgnoreCase(actor.email())).isPresent();
    }

    private Set<String> importersOf(String sourceProjectId, String safeOld) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.addAll(importersInSharedStore(safeOld));
        candidates.addAll(importersListedInMetadata(safeOld));
        candidates.remove(sourceProjectId);
        Set<String> verified = new LinkedHashSet<>();
        for (String projectId : candidates) {
            try {
                if (datasetService.execAsk(projectId, "ASK { ?o <" + OWL_IMPORTS + "> <" + safeOld + "> }")) {
                    verified.add(projectId);
                }
            } catch (RuntimeException e) {
                log.debug("[IMPORTS] Could not check project {} for imports of {}: {}", projectId, safeOld, e.getMessage());
            }
        }
        return verified;
    }

    private Set<String> importersInSharedStore(String safeOld) {
        Set<String> projects = new LinkedHashSet<>();
        String query = "SELECT DISTINCT ?g WHERE { GRAPH ?g { ?o <" + OWL_IMPORTS + "> <" + safeOld + "> } "
                + "FILTER(STRSTARTS(STR(?g), \"" + PROJECT_GRAPH_PREFIX + "\")) }";
        try (RepositoryConnection connection = datasetService.getRepository().getConnection();
             TupleQueryResult rows = connection.prepareTupleQuery(query).evaluate()) {
            while (rows.hasNext()) {
                BindingSet row = rows.next();
                String graph = row.getValue("g").stringValue();
                projects.add(URLDecoder.decode(graph.substring(PROJECT_GRAPH_PREFIX.length()), StandardCharsets.UTF_8));
            }
        } catch (RuntimeException e) {
            log.warn("[IMPORTS] Could not search the shared store for imports of {}: {}", safeOld, e.getMessage());
        }
        return projects;
    }

    private Set<String> importersListedInMetadata(String safeOld) {
        Set<String> projects = new LinkedHashSet<>();
        try {
            for (ProjectDocument document : mongoTemplate.find(Query.query(Criteria.where("metadata.imports").is(safeOld)),
                    ProjectDocument.class)) {
                projects.add(document.getId());
            }
        } catch (RuntimeException e) {
            log.debug("[IMPORTS] Could not read imports from the project metadata: {}", e.getMessage());
        }
        return projects;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
