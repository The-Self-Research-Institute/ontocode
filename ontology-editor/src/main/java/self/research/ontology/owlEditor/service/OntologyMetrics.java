package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

@Slf4j
final class OntologyMetrics {

    private static final String PREFIXES = OntologyMetadataService.PREFIXES;

    record OwlApiAxiomCounts(int total, int logical, int declarations) {}

    private final SparqlDatasetService datasetService;
    private final ProjectMetadataService projectMetadataService;
    private final ProjectImportService importService;
    private final ManchesterExpressionService manchesterExpressionService;
    private final OntologyCountQueries queries;
    private final Function<String, String> ontologyIri;
    private final StampedComputeCache<OwlApiAxiomCounts> owlCounts = new StampedComputeCache<>();

    OntologyMetrics(SparqlDatasetService datasetService, ProjectMetadataService projectMetadataService,
                    ProjectImportService importService, ManchesterExpressionService manchesterExpressionService,
                    OntologyCountQueries queries, Function<String, String> ontologyIri) {
        this.datasetService = datasetService;
        this.projectMetadataService = projectMetadataService;
        this.importService = importService;
        this.manchesterExpressionService = manchesterExpressionService;
        this.queries = queries;
        this.ontologyIri = ontologyIri;
    }

    Map<String, Object> getDynamicMetrics(String projectId) {
        Map<String, Object> metrics = new HashMap<>();
        
        log.info("Calculating dynamic metrics for project: {}", projectId);

        String typeCounts = PREFIXES + """
            SELECT ?type (COUNT(DISTINCT ?s) AS ?count) WHERE {
              ?s a ?type .
              FILTER(isIRI(?s))
              VALUES ?type {
                owl:Class owl:ObjectProperty owl:DatatypeProperty
                owl:AnnotationProperty owl:NamedIndividual
                owl:FunctionalProperty owl:InverseFunctionalProperty
                owl:TransitiveProperty owl:SymmetricProperty
                owl:AsymmetricProperty owl:ReflexiveProperty
                owl:IrreflexiveProperty owl:NegativePropertyAssertion
              }
            }
            GROUP BY ?type
            """;
        Map<String, Integer> typeCountMap = new HashMap<>();
        boolean typeCountsFailed = false;
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, typeCounts);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("type") && sol.hasBinding("count")) {
                    typeCountMap.put(sol.getValue("type").stringValue(),
                            Integer.parseInt(sol.getValue("count").stringValue()));
                }
            }
        } catch (Exception e) {
            typeCountsFailed = true;
            log.error("Error getting type counts for project {}", projectId, e);
        }

        int classCount = typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#Class", 0);
        int objectPropertyCount = typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#ObjectProperty", 0);
        int dataPropertyCount = typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#DatatypeProperty", 0);
        int annotationPropertyCount = queries.getAnnotationPropertyUsageCount(projectId);
        int individualCount = typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#NamedIndividual", 0);

        metrics.put("classCount", classCount);
        metrics.put("objectPropertyCount", objectPropertyCount);
        metrics.put("dataPropertyCount", dataPropertyCount);
        metrics.put("annotationPropertyCount", annotationPropertyCount);
        metrics.put("individualCount", individualCount);
        metrics.put("datatypeCount", queries.getDatatypeUsageCount(projectId));
        if (typeCountsFailed) {
            metrics.put("metricsFailed", true);
        }

        int tripleCount = (int) datasetService.getDatasetSize(projectId);
        metrics.put("tripleCount", tripleCount);

        OwlApiAxiomCounts owlCounts = getOwlApiAxiomCounts(projectId);
        metrics.put("axiomCount", owlCounts.total());
        metrics.put("logicalAxiomCount", owlCounts.logical());
        metrics.put("declarationAxiomCount", owlCounts.declarations());

        metrics.put("functionalObjectPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#FunctionalProperty", 0));
        metrics.put("inverseFunctionalObjectPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#InverseFunctionalProperty", 0));
        metrics.put("transitiveObjectPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#TransitiveProperty", 0));
        metrics.put("symmetricObjectPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#SymmetricProperty", 0));
        metrics.put("asymmetricObjectPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#AsymmetricProperty", 0));
        metrics.put("reflexiveObjectPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#ReflexiveProperty", 0));
        metrics.put("irreflexiveObjectPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#IrreflexiveProperty", 0));
        metrics.put("negativeObjectPropertyAssertionAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#NegativePropertyAssertion", 0));
        metrics.put("negativeDataPropertyAssertionAxiomCount", 0);
        metrics.put("functionalDataPropertyAxiomCount", typeCountMap.getOrDefault("http://www.w3.org/2002/07/owl#FunctionalProperty", 0));

        String predicateCounts = PREFIXES + """
            SELECT ?pred (COUNT(*) AS ?count) WHERE {
              ?s ?pred ?o .
              VALUES ?pred {
                rdfs:subClassOf owl:equivalentClass owl:disjointWith
                rdfs:subPropertyOf owl:equivalentProperty owl:inverseOf
                owl:propertyDisjointWith rdfs:domain rdfs:range
                rdf:type owl:sameAs owl:differentFrom owl:propertyChainAxiom
              }
            }
            GROUP BY ?pred
            """;
        Map<String, Integer> predCountMap = new HashMap<>();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, predicateCounts);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("pred") && sol.hasBinding("count")) {
                    predCountMap.put(sol.getValue("pred").stringValue(),
                            Integer.parseInt(sol.getValue("count").stringValue()));
                }
            }
        } catch (Exception e) {
            log.error("Error getting predicate counts for project {}", projectId, e);
        }

        Map<String, Integer> typedCounts = queries.getTypedAxiomCounts(projectId);
        int objectDomainCount = typedCounts.getOrDefault("ObjectProperty|domain", 0);
        int objectRangeCount = typedCounts.getOrDefault("ObjectProperty|range", 0);
        int dataDomainCount = typedCounts.getOrDefault("DatatypeProperty|domain", 0);
        int dataRangeCount = typedCounts.getOrDefault("DatatypeProperty|range", 0);
        int subPropCount = predCountMap.getOrDefault("http://www.w3.org/2000/01/rdf-schema#subPropertyOf", 0);
        int equivPropCount = predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#equivalentProperty", 0);
        int disjPropCount = predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#propertyDisjointWith", 0);

        metrics.put("subClassOfAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2000/01/rdf-schema#subClassOf", 0));
        metrics.put("equivalentClassesAxiomCount", typedCounts.getOrDefault("equivalentClasses", 0));
        int pairwiseDisjointWith = predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#disjointWith", 0);
        metrics.put("disjointClassesAxiomCount", pairwiseDisjointWith + typedCounts.getOrDefault("allDisjointClasses", 0));
        metrics.put("subObjectPropertyOfAxiomCount", subPropCount);
        metrics.put("equivalentObjectPropertiesAxiomCount", equivPropCount);
        metrics.put("inverseObjectPropertiesAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#inverseOf", 0));
        metrics.put("disjointObjectPropertiesAxiomCount", disjPropCount);
        metrics.put("objectPropertyDomainAxiomCount", objectDomainCount);
        metrics.put("objectPropertyRangeAxiomCount", objectRangeCount);
        metrics.put("subDataPropertyOfAxiomCount", subPropCount);
        metrics.put("equivalentDataPropertiesAxiomCount", equivPropCount);
        metrics.put("disjointDataPropertiesAxiomCount", disjPropCount);
        metrics.put("dataPropertyDomainAxiomCount", dataDomainCount);
        metrics.put("dataPropertyRangeAxiomCount", dataRangeCount);
        metrics.put("classAssertionAxiomCount", queries.getClassAssertionCount(projectId));
        metrics.put("sameIndividualAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#sameAs", 0));
        metrics.put("differentIndividualsAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#differentFrom", 0));
        metrics.put("subPropertyChainOfAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#propertyChainAxiom", 0));
        metrics.put("annotationPropertyDomainAxiomCount", typedCounts.getOrDefault("AnnotationProperty|domain", 0));
        metrics.put("annotationPropertyRangeAxiomCount", typedCounts.getOrDefault("AnnotationProperty|range", 0));
        metrics.put("subAnnotationPropertyOfAxiomCount", subPropCount);

        String predTypeCounts = PREFIXES + """
            SELECT ?ptype (COUNT(*) AS ?count) WHERE {
              ?s ?p ?o .
              ?p a ?ptype .
              VALUES ?ptype { owl:ObjectProperty owl:DatatypeProperty owl:AnnotationProperty }
            }
            GROUP BY ?ptype
            """;
        Map<String, Integer> predTypeMap = new HashMap<>();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, predTypeCounts);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("ptype") && sol.hasBinding("count")) {
                    predTypeMap.put(sol.getValue("ptype").stringValue(),
                            Integer.parseInt(sol.getValue("count").stringValue()));
                }
            }
        } catch (Exception e) {
            log.error("Error getting predicate-type counts for project {}", projectId, e);
        }

        metrics.put("objectPropertyAssertionAxiomCount", predTypeMap.getOrDefault("http://www.w3.org/2002/07/owl#ObjectProperty", 0));
        metrics.put("dataPropertyAssertionAxiomCount", predTypeMap.getOrDefault("http://www.w3.org/2002/07/owl#DatatypeProperty", 0));
        metrics.put("annotationAssertionAxiomCount", queries.getAnnotationAssertionCount(projectId));

        metrics.put("gciCount", queries.getGCICount(projectId, ontologyIri.apply(projectId)));
        
        metrics.put("ontologyIRI", ontologyIri.apply(projectId));
        
        return metrics;
    }

    Map<String, Object> getPropertyAndAssertionAxiomCounts(String projectId) {
        Map<String, Object> metrics = new HashMap<>();

        String predicateCounts = PREFIXES + """
            SELECT ?pred (COUNT(*) AS ?count) WHERE {
              ?s ?pred ?o .
              VALUES ?pred {
                rdfs:subClassOf owl:equivalentClass owl:disjointWith
                rdfs:subPropertyOf owl:equivalentProperty owl:inverseOf
                owl:propertyDisjointWith rdfs:domain rdfs:range
                rdf:type owl:sameAs owl:differentFrom owl:propertyChainAxiom
              }
            }
            GROUP BY ?pred
            """;
        Map<String, Integer> predCountMap = new HashMap<>();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, predicateCounts);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("pred") && sol.hasBinding("count")) {
                    predCountMap.put(sol.getValue("pred").stringValue(),
                            Integer.parseInt(sol.getValue("count").stringValue()));
                }
            }
        } catch (Exception e) {
            log.error("Error getting predicate counts (backfill) for project {}", projectId, e);
        }

        Map<String, Integer> typedCounts = queries.getTypedAxiomCounts(projectId);
        int objectDomainCount = typedCounts.getOrDefault("ObjectProperty|domain", 0);
        int objectRangeCount = typedCounts.getOrDefault("ObjectProperty|range", 0);
        int dataDomainCount = typedCounts.getOrDefault("DatatypeProperty|domain", 0);
        int dataRangeCount = typedCounts.getOrDefault("DatatypeProperty|range", 0);
        int subPropCount = predCountMap.getOrDefault("http://www.w3.org/2000/01/rdf-schema#subPropertyOf", 0);
        int equivPropCount = predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#equivalentProperty", 0);
        int disjPropCount = predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#propertyDisjointWith", 0);
        int pairwiseDisjointWith = predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#disjointWith", 0);

        metrics.put("equivalentClassesAxiomCount", typedCounts.getOrDefault("equivalentClasses", 0));
        metrics.put("disjointClassesAxiomCount", pairwiseDisjointWith + typedCounts.getOrDefault("allDisjointClasses", 0));
        metrics.put("subObjectPropertyOfAxiomCount", subPropCount);
        metrics.put("equivalentObjectPropertiesAxiomCount", equivPropCount);
        metrics.put("inverseObjectPropertiesAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#inverseOf", 0));
        metrics.put("disjointObjectPropertiesAxiomCount", disjPropCount);
        metrics.put("objectPropertyDomainAxiomCount", objectDomainCount);
        metrics.put("objectPropertyRangeAxiomCount", objectRangeCount);
        metrics.put("subDataPropertyOfAxiomCount", subPropCount);
        metrics.put("equivalentDataPropertiesAxiomCount", equivPropCount);
        metrics.put("disjointDataPropertiesAxiomCount", disjPropCount);
        metrics.put("dataPropertyDomainAxiomCount", dataDomainCount);
        metrics.put("dataPropertyRangeAxiomCount", dataRangeCount);
        metrics.put("classAssertionAxiomCount", queries.getClassAssertionCount(projectId));
        metrics.put("sameIndividualAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#sameAs", 0));
        metrics.put("differentIndividualsAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#differentFrom", 0));
        metrics.put("subPropertyChainOfAxiomCount", predCountMap.getOrDefault("http://www.w3.org/2002/07/owl#propertyChainAxiom", 0));
        metrics.put("annotationPropertyDomainAxiomCount", typedCounts.getOrDefault("AnnotationProperty|domain", 0));
        metrics.put("annotationPropertyRangeAxiomCount", typedCounts.getOrDefault("AnnotationProperty|range", 0));
        metrics.put("subAnnotationPropertyOfAxiomCount", subPropCount);

        String predTypeCounts = PREFIXES + """
            SELECT ?ptype (COUNT(*) AS ?count) WHERE {
              ?s ?p ?o .
              ?p a ?ptype .
              VALUES ?ptype { owl:ObjectProperty owl:DatatypeProperty owl:AnnotationProperty }
            }
            GROUP BY ?ptype
            """;
        Map<String, Integer> predTypeMap = new HashMap<>();
        try {
            TupleQueryResult rs = datasetService.execSelect(projectId, predTypeCounts);
            while (rs.hasNext()) {
                BindingSet sol = rs.next();
                if (sol.hasBinding("ptype") && sol.hasBinding("count")) {
                    predTypeMap.put(sol.getValue("ptype").stringValue(),
                            Integer.parseInt(sol.getValue("count").stringValue()));
                }
            }
        } catch (Exception e) {
            log.error("Error getting predicate-type counts (backfill) for project {}", projectId, e);
        }

        metrics.put("objectPropertyAssertionAxiomCount", predTypeMap.getOrDefault("http://www.w3.org/2002/07/owl#ObjectProperty", 0));
        metrics.put("dataPropertyAssertionAxiomCount", predTypeMap.getOrDefault("http://www.w3.org/2002/07/owl#DatatypeProperty", 0));
        metrics.put("annotationAssertionAxiomCount", queries.getAnnotationAssertionCount(projectId));

        return metrics;
    }

    void overlayLiveDesktopEntityCounts(String projectId, Map<String, Object> metadata,
                                        ProjectOntologyCache ontologyCache) {
        if (ontologyCache == null || importService == null || !importService.isFusekiSyncPending(projectId)) {
            return;
        }
        ontologyCache.get(projectId).ifPresent(cached -> {
            OWLOntology ontology = cached.ontology();
            Imports imp = Imports.INCLUDED;
            int classes = (int) ontology.classesInSignature(imp).filter(c -> !c.isBuiltIn()).count();
            int objectProps = (int) ontology.objectPropertiesInSignature(imp).filter(p -> !p.isBuiltIn()).count();
            int dataProps = (int) ontology.dataPropertiesInSignature(imp).filter(p -> !p.isBuiltIn()).count();
            int individuals = (int) ontology.individualsInSignature(imp).filter(i -> !i.isBuiltIn()).count();

            metadata.put("classCount", classes);
            metadata.put("objectPropertyCount", objectProps);
            metadata.put("dataPropertyCount", dataProps);
            metadata.put("individualCount", individuals);

            Map<String, Object> counts = new LinkedHashMap<>();
            if (metadata.get("counts") instanceof Map<?, ?> existing) {
                existing.forEach((k, v) -> counts.put(String.valueOf(k), v));
            }
            counts.put("classes", classes);
            counts.put("objectProperties", objectProps);
            counts.put("dataProperties", dataProps);
            counts.put("individuals", individuals);
            metadata.put("counts", counts);


            metadata.put("axiomCount", ontology.getAxiomCount(imp));
            metadata.put("logicalAxiomCount", ontology.getLogicalAxiomCount(imp));
            Map<String, AxiomType<?>> axiomTypes = new LinkedHashMap<>();
            axiomTypes.put("declarationAxiomCount", AxiomType.DECLARATION);
            axiomTypes.put("subClassOfAxiomCount", AxiomType.SUBCLASS_OF);
            axiomTypes.put("equivalentClassesAxiomCount", AxiomType.EQUIVALENT_CLASSES);
            axiomTypes.put("disjointClassesAxiomCount", AxiomType.DISJOINT_CLASSES);
            axiomTypes.put("subObjectPropertyOfAxiomCount", AxiomType.SUB_OBJECT_PROPERTY);
            axiomTypes.put("inverseObjectPropertiesAxiomCount", AxiomType.INVERSE_OBJECT_PROPERTIES);
            axiomTypes.put("objectPropertyDomainAxiomCount", AxiomType.OBJECT_PROPERTY_DOMAIN);
            axiomTypes.put("objectPropertyRangeAxiomCount", AxiomType.OBJECT_PROPERTY_RANGE);
            axiomTypes.put("dataPropertyDomainAxiomCount", AxiomType.DATA_PROPERTY_DOMAIN);
            axiomTypes.put("dataPropertyRangeAxiomCount", AxiomType.DATA_PROPERTY_RANGE);
            axiomTypes.put("classAssertionAxiomCount", AxiomType.CLASS_ASSERTION);
            axiomTypes.put("objectPropertyAssertionAxiomCount", AxiomType.OBJECT_PROPERTY_ASSERTION);
            axiomTypes.put("dataPropertyAssertionAxiomCount", AxiomType.DATA_PROPERTY_ASSERTION);
            axiomTypes.put("annotationAssertionAxiomCount", AxiomType.ANNOTATION_ASSERTION);
            axiomTypes.forEach((key, type) -> metadata.put(key, ontology.getAxiomCount(type, imp)));

            log.debug("[OwlApiDesktop] Fuseki sync pending for {} — entity and axiom counts from live model (classes={}, axioms={})",
                    projectId, classes, metadata.get("axiomCount"));
        });
    }

    OwlApiAxiomCounts getOwlApiAxiomCounts(String projectId) {
        return owlCounts.get(projectId, projectMetadataService.versionStamp(projectId),
                () -> computeOwlApiAxiomCounts(projectId), new OwlApiAxiomCounts(0, 0, 0));
    }

    private OwlApiAxiomCounts computeOwlApiAxiomCounts(String projectId) {
        long started = System.nanoTime();
        try {
            OWLOntology ont = manchesterExpressionService.loadFreshOntology(projectId);
            int total = ont.getAxiomCount(Imports.INCLUDED);
            int logical = ont.getLogicalAxiomCount(Imports.INCLUDED);
            int declarations = ont.getAxiomCount(AxiomType.DECLARATION, Imports.INCLUDED);
            log.info("[PERF] OWLAPI axiom counts for project {} computed in {}ms", projectId,
                    (System.nanoTime() - started) / 1_000_000);
            return new OwlApiAxiomCounts(total, logical, declarations);
        } catch (Exception e) {
            log.error("Error computing OWLAPI axiom counts for project {}", projectId, e);
            return null;
        }
    }
}
