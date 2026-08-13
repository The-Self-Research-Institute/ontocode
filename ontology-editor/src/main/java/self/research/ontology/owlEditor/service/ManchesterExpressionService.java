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

@Service
@Slf4j
public class ManchesterExpressionService {

    private final StorageManager storageManager;
    private final SparqlDatasetService datasetService;
    private final OntologyMutationService mutationService;

    @Autowired(required = false)
    private ProjectOntologyCache ontologyCache;

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

    private OWLOntology loadOntology(String projectId, boolean draft, String userId) throws Exception {
        if (draft && userId != null && !userId.isBlank()) {
            String draftGraph = datasetService.getDraftGraphUri(projectId, userId);
            String rdf = datasetService.exportNamedGraph(
                    projectId, draftGraph, org.eclipse.rdf4j.rio.RDFFormat.RDFXML);
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

            OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
            return manager.loadOntologyFromOntologyDocument(
                    new org.semanticweb.owlapi.io.StringDocumentSource(rdf), config);
        }

        if (ontologyCache != null) {
            Optional<ProjectOntologyCache.CachedOntology> cached = ontologyCache.get(projectId);
            if (cached.isPresent()) {
                log.debug("[Manchester] Using cached OWLAPI model for project {}", projectId);
                return cached.get().ontology();
            }
        }

        log.info("[Manchester] OWLAPI cache miss for project {} — exporting from Fuseki (slow path)", projectId);
        Path exportPath = storageManager.exportOntology(projectId, "rdfxml");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        return manager.loadOntologyFromOntologyDocument(exportPath.toFile());
    }

    private static final java.util.regex.Pattern BARE_IRI =
            java.util.regex.Pattern.compile("(?<!<)\\b(https?://[^\\s()<>]+|urn:[^\\s()<>]+)");

    private String wrapBareIris(String expression) {
        return BARE_IRI.matcher(expression).replaceAll("<$1>");
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
        parser.setStringToParse(wrapBareIris(expression));
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
        parser.setStringToParse(wrapBareIris(expression));
        return parser.parseDataRange();
    }
}
