package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.service.ImportQueueManager;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class OntologyPreparseService {

    private static final Logger log = LoggerFactory.getLogger(OntologyPreparseService.class);
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static final IRI RDF_TYPE = VF.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
    private static final IRI OWL_CLASS = VF.createIRI("http://www.w3.org/2002/07/owl#Class");
    private static final IRI OWL_ANNOTATION_PROPERTY = VF.createIRI("http://www.w3.org/2002/07/owl#AnnotationProperty");

    private final ProjectMetadataService metadataService;
    private final ImportQueueManager queueManager;

    public OntologyPreparseService(ProjectMetadataService metadataService, ImportQueueManager queueManager) {
        this.metadataService = metadataService;
        this.queueManager = queueManager;
    }

    @Async("owlParsingExecutor")
    public void preparse(Path owlFile, String projectId, RDFFormat format) {
        long start = System.nanoTime();
        AtomicLong tripleCount = new AtomicLong(0);
        AtomicLong classCount = new AtomicLong(0);
        AtomicLong annotationCount = new AtomicLong(0);

        try (InputStream in = Files.newInputStream(owlFile)) {
            RDFParser parser = Rio.createParser(format);
            parser.setRDFHandler(new AbstractRDFHandler() {
                @Override
                public void handleStatement(Statement st) {
                    tripleCount.incrementAndGet();
                    if (RDF_TYPE.equals(st.getPredicate())) {
                        if (OWL_CLASS.equals(st.getObject())) {
                            classCount.incrementAndGet();
                        } else if (OWL_ANNOTATION_PROPERTY.equals(st.getObject())) {
                            annotationCount.incrementAndGet();
                        }
                    }
                }
            });
            parser.parse(in, "http://ontocode.org/preparse/" + projectId);

            Map<String, Object> meta = metadataService.readMeta(projectId)
                    .map(HashMap::new)
                    .orElseGet(HashMap::new);

            Map<String, Object> preparse = new HashMap<>();
            preparse.put("triples", tripleCount.get());
            preparse.put("classes", classCount.get());
            preparse.put("annotationProperties", annotationCount.get());
            preparse.put("timestamp", Instant.now().toString());
            preparse.put("durationMs", (System.nanoTime() - start) / 1_000_000);

            meta.put("preparse", preparse);
            metadataService.writeMeta(projectId, meta);

            queueManager.updateItemMetrics(projectId, Files.size(owlFile),
                    (int) classCount.get(), (int) annotationCount.get());

            log.info("[Preparse {}] Parsed {} triples (classes={}, annotations={}) in {} ms",
                    projectId, tripleCount.get(), classCount.get(), annotationCount.get(),
                    (System.nanoTime() - start) / 1_000_000);
        } catch (Exception e) {
            log.warn("[Preparse {}] Failed: {}", projectId, e.getMessage());
        }
    }
}
