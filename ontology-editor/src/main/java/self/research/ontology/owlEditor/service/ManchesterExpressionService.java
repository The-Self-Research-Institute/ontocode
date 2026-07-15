package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.expression.ShortFormEntityChecker;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.BidirectionalShortFormProvider;
import org.semanticweb.owlapi.util.BidirectionalShortFormProviderAdapter;
import org.semanticweb.owlapi.util.ShortFormProvider;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.util.OwlAxiomSparqlWriter;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Shared Manchester OWL Syntax parse + persist for class expressions and GCAs.
 */
@Service
@Slf4j
public class ManchesterExpressionService {

    private final StorageManager storageManager;
    private final SparqlDatasetService datasetService;
    private final OntologyMutationService mutationService;

    /** Optional: present when fast-open is enabled. Used to skip the slow export+parse. */
    @Autowired(required = false)
    private ProjectOntologyCache ontologyCache;

    // @Lazy breaks the cycle: OntologyMutationService -> OntologyIndexService ->
    // OntologyMetadataService -> GeneralClassAxiomService -> ManchesterExpressionService.
    // Same pattern already used by OntologyMetadataService for the same bean.
    public ManchesterExpressionService(StorageManager storageManager, SparqlDatasetService datasetService,
                                       @Lazy OntologyMutationService mutationService) {
        this.storageManager = storageManager;
        this.datasetService = datasetService;
        this.mutationService = mutationService;
    }

    public OWLClassExpression parseClassExpression(String projectId, String expression) throws Exception {
        OWLOntology ontology = loadOntology(projectId);
        return parseClassExpression(ontology, expression);
    }

    public void addGeneralClassAxiom(String projectId, String subClassExpr, String superClassExpr) throws Exception {
        addGeneralClassAxiom(projectId, subClassExpr, superClassExpr, false, null);
    }

    /**
     * Draft-aware variant: when {@code draft} is true, the axiom is written to the user's
     * private draft graph instead of the shared/public ontology.
     */
    public void addGeneralClassAxiom(String projectId, String subClassExpr, String superClassExpr,
                                     boolean draft, String userId) throws Exception {
        OWLOntology ontology = loadOntology(projectId, draft, userId);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();

        OWLClassExpression subClass = parseClassExpression(ontology, subClassExpr.trim());
        OWLClassExpression superClass = parseClassExpression(ontology, superClassExpr.trim());

        if (subClass == null || superClass == null) {
            throw new IllegalArgumentException("Could not parse GCA class expressions");
        }
        if (!subClass.isAnonymous()) {
            throw new IllegalArgumentException(
                    "GCA sub-class expression must be anonymous (e.g. 'A and B', 'p some C')");
        }

        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(subClass, superClass);
        persistAxioms(projectId, Set.of(axiom), draft, userId);
        log.info("Added GCA for project {}: {} SubClassOf {} (draft={})", projectId, subClassExpr, superClassExpr, draft);
    }

    public void addClassExpressionAxiom(String projectId, String classIri, String axiomType, String expression)
            throws Exception {
        addClassExpressionAxiom(projectId, classIri, axiomType, expression, false, null);
    }

    public void addClassExpressionAxiom(String projectId, String classIri, String axiomType, String expression,
                                        boolean draft, String userId)
            throws Exception {
        if (classIri == null || classIri.isBlank()) {
            throw new IllegalArgumentException("classIri is required");
        }
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression is required");
        }
        if (axiomType == null || axiomType.isBlank()) {
            throw new IllegalArgumentException("axiomType is required");
        }

        OWLOntology ontology = loadOntology(projectId, draft, userId);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLClass namedClass = df.getOWLClass(IRI.create(classIri.trim()));
        OWLClassExpression expr = parseClassExpression(ontology, expression.trim());

        OWLAxiom axiom = switch (axiomType) {
            case "EquivalentTo" -> df.getOWLEquivalentClassesAxiom(namedClass, expr);
            case "SubClassOf" -> df.getOWLSubClassOfAxiom(namedClass, expr);
            case "DisjointWith" -> df.getOWLDisjointClassesAxiom(namedClass, expr);
            default -> throw new IllegalArgumentException("Unsupported axiomType: " + axiomType);
        };

        persistAxioms(projectId, Set.of(axiom), draft, userId);
        log.info("Added {} axiom for {} in project {} (draft={}): {}", axiomType, classIri, projectId, draft, expression);
    }

    public void addPropertyDomainAxiom(String projectId, String propertyIri, String expression, boolean isDataProperty)
            throws Exception {
        addPropertyDomainAxiom(projectId, propertyIri, expression, isDataProperty, false, null);
    }

    public void addPropertyDomainAxiom(String projectId, String propertyIri, String expression, boolean isDataProperty,
                                       boolean draft, String userId)
            throws Exception {
        if (propertyIri == null || propertyIri.isBlank()) {
            throw new IllegalArgumentException("propertyIri is required");
        }
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression is required");
        }

        OWLOntology ontology = loadOntology(projectId, draft, userId);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLClassExpression domain = parseClassExpression(ontology, expression.trim());

        OWLAxiom axiom = isDataProperty
                ? df.getOWLDataPropertyDomainAxiom(df.getOWLDataProperty(IRI.create(propertyIri.trim())), domain)
                : df.getOWLObjectPropertyDomainAxiom(df.getOWLObjectProperty(IRI.create(propertyIri.trim())), domain);

        persistAxioms(projectId, Set.of(axiom), draft, userId);
        log.info("Added property domain axiom for {} in project {} (draft={}): {}", propertyIri, projectId, draft, expression);
    }

    public void addPropertyRangeAxiom(String projectId, String propertyIri, String expression, boolean isDataProperty)
            throws Exception {
        addPropertyRangeAxiom(projectId, propertyIri, expression, isDataProperty, false, null);
    }

    public void addPropertyRangeAxiom(String projectId, String propertyIri, String expression, boolean isDataProperty,
                                      boolean draft, String userId)
            throws Exception {
        if (propertyIri == null || propertyIri.isBlank()) {
            throw new IllegalArgumentException("propertyIri is required");
        }
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression is required");
        }

        OWLOntology ontology = loadOntology(projectId, draft, userId);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        IRI propIri = IRI.create(propertyIri.trim());

        OWLAxiom axiom;
        if (isDataProperty) {
            OWLDataRange range = parseDataRange(ontology, expression.trim());
            axiom = df.getOWLDataPropertyRangeAxiom(df.getOWLDataProperty(propIri), range);
        } else {
            OWLClassExpression range = parseClassExpression(ontology, expression.trim());
            axiom = df.getOWLObjectPropertyRangeAxiom(df.getOWLObjectProperty(propIri), range);
        }

        persistAxioms(projectId, Set.of(axiom), draft, userId);
        log.info("Added property range axiom for {} in project {} (draft={}): {}", propertyIri, projectId, draft, expression);
    }

    public void deletePropertyDomainAxiom(String projectId, String propertyIri, String expression, boolean isDataProperty)
            throws Exception {
        deletePropertyDomainAxiom(projectId, propertyIri, expression, isDataProperty, false, null);
    }

    public void deletePropertyDomainAxiom(String projectId, String propertyIri, String expression, boolean isDataProperty,
                                          boolean draft, String userId)
            throws Exception {
        OWLAxiom axiom = buildPropertyDomainAxiom(projectId, propertyIri, expression, isDataProperty, draft, userId);
        deleteAxioms(projectId, Set.of(axiom), draft, userId);
    }

    public void deletePropertyRangeAxiom(String projectId, String propertyIri, String expression, boolean isDataProperty)
            throws Exception {
        deletePropertyRangeAxiom(projectId, propertyIri, expression, isDataProperty, false, null);
    }

    public void deletePropertyRangeAxiom(String projectId, String propertyIri, String expression, boolean isDataProperty,
                                         boolean draft, String userId)
            throws Exception {
        OWLAxiom axiom = buildPropertyRangeAxiom(projectId, propertyIri, expression, isDataProperty, draft, userId);
        deleteAxioms(projectId, Set.of(axiom), draft, userId);
    }

    private OWLAxiom buildPropertyDomainAxiom(String projectId, String propertyIri, String expression,
                                              boolean isDataProperty, boolean draft, String userId) throws Exception {
        OWLOntology ontology = loadOntology(projectId, draft, userId);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLClassExpression domain = parseClassExpression(ontology, expression.trim());
        return isDataProperty
                ? df.getOWLDataPropertyDomainAxiom(df.getOWLDataProperty(IRI.create(propertyIri.trim())), domain)
                : df.getOWLObjectPropertyDomainAxiom(df.getOWLObjectProperty(IRI.create(propertyIri.trim())), domain);
    }

    private OWLAxiom buildPropertyRangeAxiom(String projectId, String propertyIri, String expression,
                                             boolean isDataProperty, boolean draft, String userId) throws Exception {
        OWLOntology ontology = loadOntology(projectId, draft, userId);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        IRI propIri = IRI.create(propertyIri.trim());
        if (isDataProperty) {
            OWLDataRange range = parseDataRange(ontology, expression.trim());
            return df.getOWLDataPropertyRangeAxiom(df.getOWLDataProperty(propIri), range);
        }
        OWLClassExpression range = parseClassExpression(ontology, expression.trim());
        return df.getOWLObjectPropertyRangeAxiom(df.getOWLObjectProperty(propIri), range);
    }

    private void persistAxioms(String projectId, Set<? extends OWLAxiom> axioms) throws Exception {
        persistAxioms(projectId, axioms, false, null);
    }

    private void persistAxioms(String projectId, Set<? extends OWLAxiom> axioms, boolean draft, String userId)
            throws Exception {
        String sparql = OwlAxiomSparqlWriter.toInsertData(axioms);
        if (sparql.isBlank()) {
            throw new IllegalStateException("Failed to serialize OWL axiom");
        }
        mutationService.applyRawUpdate(projectId, sparql, draft, userId);
    }

    private void deleteAxioms(String projectId, Set<? extends OWLAxiom> axioms) throws Exception {
        deleteAxioms(projectId, axioms, false, null);
    }

    private void deleteAxioms(String projectId, Set<? extends OWLAxiom> axioms, boolean draft, String userId)
            throws Exception {
        String sparql = OwlAxiomSparqlWriter.toDeleteData(axioms);
        if (sparql.isBlank()) {
            throw new IllegalStateException("Failed to serialize OWL axiom for deletion");
        }
        mutationService.applyRawUpdate(projectId, sparql, draft, userId);
    }

    private OWLOntology loadOntology(String projectId) throws Exception {
        return loadOntology(projectId, false, null);
    }

    /**
     * Draft-aware model load. In draft mode the copy-on-switch draft graph is a FULL snapshot
     * (baseline main + the user's edits), so exporting it yields a complete model in which the
     * Manchester parser can resolve draft-only entities — parsing against the public model would
     * fail to find them. Public mode keeps the fast cache / main-export path.
     */
    private OWLOntology loadOntology(String projectId, boolean draft, String userId) throws Exception {
        if (draft && userId != null && !userId.isBlank()) {
            String draftGraph = datasetService.getDraftGraphUri(projectId, userId);
            String rdf = datasetService.exportNamedGraph(
                    projectId, draftGraph, org.eclipse.rdf4j.rio.RDFFormat.RDFXML);
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            // Tolerate unresolvable owl:imports — the ontology may import IRIs that aren't
            // network-resolvable (common), and we only need the local axioms for name resolution.
            OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
            return manager.loadOntologyFromOntologyDocument(
                    new org.semanticweb.owlapi.io.StringDocumentSource(rdf), config);
        }
        // Fast path: use the in-memory OWLAPI model when it is already warm.
        // This avoids a full Fuseki export + re-parse which can take 1-2 minutes for large ontologies.
        if (ontologyCache != null) {
            Optional<ProjectOntologyCache.CachedOntology> cached = ontologyCache.get(projectId);
            if (cached.isPresent()) {
                log.debug("[Manchester] Using cached OWLAPI model for project {}", projectId);
                return cached.get().ontology();
            }
        }
        // Slow fallback: export from Fuseki and parse (cold cache or fast-open disabled).
        log.info("[Manchester] OWLAPI cache miss for project {} — exporting from Fuseki (slow path)", projectId);
        Path exportPath = storageManager.exportOntology(projectId, "rdfxml");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        return manager.loadOntologyFromOntologyDocument(exportPath.toFile());
    }

    private OWLClassExpression parseClassExpression(OWLOntology ontology, String expression) throws Exception {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        ShortFormProvider shortFormProvider = new SimpleShortFormProvider();
        Set<OWLOntology> importsClosure = ontology.getImportsClosure();
        BidirectionalShortFormProvider bidiProvider = new BidirectionalShortFormProviderAdapter(
                manager, importsClosure, shortFormProvider);
        OWLEntityChecker entityChecker = new ShortFormEntityChecker(bidiProvider);

        ManchesterOWLSyntaxParser parser = OWLManager.createManchesterParser();
        parser.setOWLEntityChecker(entityChecker);
        parser.setDefaultOntology(ontology);
        parser.setStringToParse(expression);
        return parser.parseClassExpression();
    }

    private OWLDataRange parseDataRange(OWLOntology ontology, String expression) throws Exception {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        ShortFormProvider shortFormProvider = new SimpleShortFormProvider();
        Set<OWLOntology> importsClosure = ontology.getImportsClosure();
        BidirectionalShortFormProvider bidiProvider = new BidirectionalShortFormProviderAdapter(
                manager, importsClosure, shortFormProvider);
        OWLEntityChecker entityChecker = new ShortFormEntityChecker(bidiProvider);

        ManchesterOWLSyntaxParser parser = OWLManager.createManchesterParser();
        parser.setOWLEntityChecker(entityChecker);
        parser.setDefaultOntology(ontology);
        parser.setStringToParse(expression);
        return parser.parseDataRange();
    }
}
