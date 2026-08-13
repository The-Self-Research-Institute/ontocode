package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.config.FastOpenCondition;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Conditional(FastOpenCondition.class)
public class InMemorySparqlOntologyMutator {

    private static final Logger log = LoggerFactory.getLogger(InMemorySparqlOntologyMutator.class);

    private final ProjectOntologyCache ontologyCache;
    private final ConcurrentHashMap<String, Object> projectLocks = new ConcurrentHashMap<>();

    public InMemorySparqlOntologyMutator(ProjectOntologyCache ontologyCache) {
        this.ontologyCache = ontologyCache;
    }

    public boolean tryApply(String projectId, String sparqlUpdate) {
        if (sparqlUpdate == null || sparqlUpdate.isBlank()) {
            return false;
        }
        if (!ontologyCache.has(projectId)) {
            return false;
        }

        Object lock = projectLocks.computeIfAbsent(projectId, id -> new Object());
        synchronized (lock) {
            var cached = ontologyCache.get(projectId);
            if (cached.isEmpty()) {
                return false;
            }
            ProjectOntologyCache.CachedOntology entry = cached.get();
            long start = System.currentTimeMillis();

            try {
                byte[] rdfXml = exportRdfXml(entry);
                byte[] updated = applySparqlUpdate(rdfXml, sparqlUpdate);
                reloadCache(projectId, entry, updated);
                log.info("[OwlApiSparql] In-memory SPARQL mutation applied for project {} in {}ms",
                        projectId, System.currentTimeMillis() - start);
                return true;
            } catch (Exception e) {
                log.warn("[OwlApiSparql] In-memory SPARQL mutation failed for project {}: {}",
                        projectId, e.getMessage());
                return false;
            }
        }
    }

    private byte[] exportRdfXml(ProjectOntologyCache.CachedOntology entry) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        entry.manager().saveOntology(entry.ontology(), new RDFXMLDocumentFormat(), out);
        return out.toByteArray();
    }

    private byte[] applySparqlUpdate(byte[] rdfXml, String sparqlUpdate) throws Exception {
        SailRepository repo = new SailRepository(new MemoryStore());
        repo.init();
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.add(new ByteArrayInputStream(rdfXml), RDFFormat.RDFXML);
            conn.prepareUpdate(QueryLanguage.SPARQL, sparqlUpdate).execute();
            Model model = org.eclipse.rdf4j.query.QueryResults.asModel(
                    conn.getStatements(null, null, null, false));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Rio.write(model, out, RDFFormat.RDFXML);
            return out.toByteArray();
        } finally {
            repo.shutDown();
        }
    }

    private void reloadCache(String projectId,
                             ProjectOntologyCache.CachedOntology previous,
                             byte[] rdfXml) throws Exception {
        Path temp = Files.createTempFile("owl-mut-" + projectId + "-", ".owl");
        try {
            Files.write(temp, rdfXml);
            OWLOntologyManager manager = OWLManager.createConcurrentOWLOntologyManager();
            manager.setOntologyLoaderConfiguration(
                    new OWLOntologyLoaderConfiguration()
                            .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                            .setLoadAnnotationAxioms(true));
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(temp.toFile());

            OWLReasoner reasoner = null;
            if (!previous.assertedHierarchyOnly()) {
                reasoner = new StructuralReasonerFactory().createNonBufferingReasoner(ontology);
                reasoner.precomputeInferences();
            }
            ontologyCache.put(projectId, ontology, reasoner, manager, previous.assertedHierarchyOnly());
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
