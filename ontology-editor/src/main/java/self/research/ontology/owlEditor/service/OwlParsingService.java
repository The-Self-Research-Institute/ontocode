package self.research.ontology.owlEditor.service;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.bson.types.ObjectId;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class OwlParsingService {

    private static final Logger logger = LoggerFactory.getLogger(OwlParsingService.class);

    @Autowired
    private GridFsTemplate gridFsTemplate;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private OntologyIndexService ontologyIndexService;

    private final OWLReasonerFactory reasonerFactory;

    public OwlParsingService() {
        this.reasonerFactory = new ReasonerFactory();
    }

    @Async
    public CompletableFuture<Void> parseAndIndex(String projectId, ObjectId fileId) {
        logger.info("Processing project {} with file {} in the background...", projectId, fileId);
        GridFSFile file = gridFsTemplate.findOne(new Query(Criteria.where("_id").is(fileId)));

        if (file == null) {
            logger.warn("File with ID {} not found in GridFS for project: {}", fileId, projectId);
            updateProjectStatus(projectId, "ERROR", "File not found in GridFS");
            return CompletableFuture.failedFuture(new RuntimeException("File not found in GridFS"));
        }

        try {
            GridFsResource resource = gridFsTemplate.getResource(file);
            try (InputStream inputStream = resource.getInputStream()) {
                logger.info("Successfully loaded {} from GridFS.", file.getFilename());
                return processOntologyStream(projectId, inputStream, file.getFilename());
            }
        } catch (IOException e) {
            logger.error("Failed to read file content from GridFS for project: {}", projectId, e);
            updateProjectStatus(projectId, "ERROR", "Failed to read OWL file: " + e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }

    @Async
    public CompletableFuture<Void> parseAndIndexFromStream(String projectId, InputStream owlStream, String filename) {
        return processOntologyStream(projectId, owlStream, filename);
    }

    private CompletableFuture<Void> processOntologyStream(String projectId, InputStream owlStream, String filename) {
        try {
            updateProjectStatus(projectId, "PROCESSING", "Starting OWL file processing...");

            OntologyParseResult result = parseOwlContent(owlStream, projectId, filename);
            indexOntologyData(projectId, result);

            updateProjectStatus(projectId, "COMPLETED",
                    String.format("Successfully processed %d classes, %d properties, %d individuals",
                            result.getClasses().size(), result.getProperties().size(), result.getIndividuals().size()));

            logger.info("Finished processing project {}", projectId);
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            logger.error("Error processing project {}: {}", projectId, e.getMessage(), e);
            updateProjectStatus(projectId, "ERROR", "Processing failed: " + e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }


    private OntologyParseResult parseOwlContent(InputStream inputStream, String projectId, String filename) {
        logger.info("Parsing OWL content for project: {}", projectId);
        OWLOntologyManager ontologyManager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = null;

        try {
            ontology = ontologyManager.loadOntologyFromOntologyDocument(inputStream);
            logger.info("Successfully loaded ontology: {}", ontology.getOntologyID());

            OWLReasoner reasoner = reasonerFactory.createReasoner(ontology);
            reasoner.precomputeInferences();

            OntologyMetadata metadata = extractMetadata(ontology, filename);
            List<ClassInfo> classInfos = extractClassInformation(ontology.getClassesInSignature(), ontology, reasoner);
            List<PropertyInfo> propertyInfos = extractPropertyInformation(ontology, reasoner);
            List<IndividualInfo> individualInfos = extractIndividualInformation(ontology.getIndividualsInSignature(), ontology, reasoner);
            List<AxiomInfo> axiomInfos = extractAxiomInformation(ontology);
            List<AnnotationPropertyInfo> annotationPropertyInfos = extractAnnotationPropertyInformation(ontology.getAnnotationPropertiesInSignature(), ontology);
            List<DatatypeInfo> datatypeInfos = extractDatatypeInformation(ontology.getDatatypesInSignature(), ontology);

            reasoner.dispose();

            return new OntologyParseResult(metadata, classInfos, propertyInfos, individualInfos, axiomInfos, annotationPropertyInfos, datatypeInfos);

        } catch (OWLOntologyCreationException e) {
            logger.error("Failed to load OWL ontology for project: {}", projectId, e);
            throw new RuntimeException("Failed to parse OWL ontology", e);
        } finally {
            if (ontology != null) {
                ontologyManager.removeOntology(ontology);
            }
        }
    }

    private List<PropertyInfo> extractPropertyInformation(OWLOntology ontology, OWLReasoner reasoner) {
        List<PropertyInfo> propertyInfos = new ArrayList<>();
        OWLDataFactory dataFactory = ontology.getOWLOntologyManager().getOWLDataFactory();

        // Process object properties
        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
            if (property.isBuiltIn()) continue;
            PropertyInfo propInfo = new PropertyInfo();
            propInfo.setIri(property.getIRI().toString());
            propInfo.setLocalName(property.getIRI().getShortForm());
            propInfo.setType("ObjectProperty");
            propInfo.setAnnotations(extractAnnotations(property, ontology));
            propInfo.setDomains(reasoner.getObjectPropertyDomains(property, true).getFlattened().stream()
                    .filter(cls -> !cls.isBuiltIn()).map(c -> c.getIRI().toString()).collect(Collectors.toList()));
            propInfo.setRanges(reasoner.getObjectPropertyRanges(property, true).getFlattened().stream()
                    .filter(cls -> !cls.isBuiltIn()).map(c -> c.getIRI().toString()).collect(Collectors.toList()));
            propInfo.setSuperProperties(reasoner.getSuperObjectProperties(property, true).getFlattened().stream()
                    .filter(p -> p.isNamed() && !p.isOWLTopObjectProperty()).map(p -> p.asOWLObjectProperty().getIRI().toString()).collect(Collectors.toList()));
            propInfo.setSubProperties(reasoner.getSubObjectProperties(property, true).getFlattened().stream()
                    .filter(p -> p.isNamed() && !p.isOWLBottomObjectProperty()).map(p -> p.asOWLObjectProperty().getIRI().toString()).collect(Collectors.toList()));
            propInfo.setCharacteristics(getPropertyCharacteristics(property, reasoner, dataFactory));
            propertyInfos.add(propInfo);
        }

        // Process data properties
        for (OWLDataProperty property : ontology.getDataPropertiesInSignature()) {
            if (property.isBuiltIn()) continue;
            PropertyInfo propInfo = new PropertyInfo();
            propInfo.setIri(property.getIRI().toString());
            propInfo.setLocalName(property.getIRI().getShortForm());
            propInfo.setType("DataProperty");
            propInfo.setAnnotations(extractAnnotations(property, ontology));
            propInfo.setDomains(reasoner.getDataPropertyDomains(property, true).getFlattened().stream()
                    .filter(cls -> !cls.isBuiltIn()).map(c -> c.getIRI().toString()).collect(Collectors.toList()));

            // FIX: This call was causing a compile error. Replaced with a more robust method
            // that gets asserted ranges directly from the ontology axioms.
            propInfo.setRanges(ontology.getDataPropertyRangeAxioms(property).stream()
                    .map(axiom -> axiom.getRange().asOWLDatatype().getIRI().toString())
                    .collect(Collectors.toList()));

            propInfo.setSuperProperties(reasoner.getSuperDataProperties(property, true).getFlattened().stream()
                    .filter(p -> !p.isOWLTopDataProperty()).map(p -> p.getIRI().toString()).collect(Collectors.toList()));
            propInfo.setSubProperties(reasoner.getSubDataProperties(property, true).getFlattened().stream()
                    .filter(p -> !p.isOWLBottomDataProperty()).map(p -> p.getIRI().toString()).collect(Collectors.toList()));
            propInfo.setCharacteristics(getPropertyCharacteristics(property, reasoner, dataFactory));
            propertyInfos.add(propInfo);
        }

        return propertyInfos;
    }

    // FIX: Replaced direct reasoner calls (e.g., isFunctional) with the more compatible
    // isEntailed(axiom) pattern. This resolves all the "cannot find symbol" errors
    // related to property characteristics.
    private List<String> getPropertyCharacteristics(OWLObjectProperty property, OWLReasoner reasoner, OWLDataFactory df) {
        List<String> characteristics = new ArrayList<>();
        if (reasoner.isEntailed(df.getOWLFunctionalObjectPropertyAxiom(property))) characteristics.add("Functional");
        if (reasoner.isEntailed(df.getOWLInverseFunctionalObjectPropertyAxiom(property))) characteristics.add("InverseFunctional");
        if (reasoner.isEntailed(df.getOWLTransitiveObjectPropertyAxiom(property))) characteristics.add("Transitive");
        if (reasoner.isEntailed(df.getOWLSymmetricObjectPropertyAxiom(property))) characteristics.add("Symmetric");
        if (reasoner.isEntailed(df.getOWLAsymmetricObjectPropertyAxiom(property))) characteristics.add("Asymmetric");
        if (reasoner.isEntailed(df.getOWLReflexiveObjectPropertyAxiom(property))) characteristics.add("Reflexive");
        if (reasoner.isEntailed(df.getOWLIrreflexiveObjectPropertyAxiom(property))) characteristics.add("Irreflexive");
        return characteristics;
    }

    private List<String> getPropertyCharacteristics(OWLDataProperty property, OWLReasoner reasoner, OWLDataFactory df) {
        List<String> characteristics = new ArrayList<>();
        if (reasoner.isEntailed(df.getOWLFunctionalDataPropertyAxiom(property))) {
            characteristics.add("Functional");
        }
        return characteristics;
    }


    // ... The rest of the file is unchanged and correct ...
    private OntologyMetadata extractMetadata(OWLOntology ontology, String filename) {
        OntologyMetadata metadata = new OntologyMetadata();
        metadata.setFilename(filename);
        metadata.setOntologyIRI(ontology.getOntologyID().getOntologyIRI().map(IRI::toString).orElse(null));
        metadata.setVersionIRI(ontology.getOntologyID().getVersionIRI().map(IRI::toString).orElse(null));
        metadata.setClassCount(ontology.getClassesInSignature().size());
        metadata.setObjectPropertyCount(ontology.getObjectPropertiesInSignature().size());
        metadata.setDataPropertyCount(ontology.getDataPropertiesInSignature().size());
        metadata.setIndividualCount(ontology.getIndividualsInSignature().size());
        metadata.setAxiomCount(ontology.getAxiomCount());
        metadata.setLogicalAxiomCount(ontology.getLogicalAxiomCount());
        metadata.setImports(ontology.getImportsDeclarations().stream().map(decl -> decl.getIRI().toString()).collect(Collectors.toList()));
        Map<String, String> annotations = new HashMap<>();
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            String key = annotation.getProperty().getIRI().getShortForm();
            String value = extractAnnotationValue(annotation.getValue());
            annotations.put(key, value);
        }
        metadata.setAnnotations(annotations);
        long gciCount = ontology.getAxioms(AxiomType.SUBCLASS_OF).stream().filter(axiom -> axiom.getSubClass().isAnonymous()).count();
        metadata.setGciCount((int) gciCount);
        metadata.setSubClassOfAxiomCount((int) ontology.getAxiomCount(AxiomType.SUBCLASS_OF));
        metadata.setEquivalentClassesAxiomCount((int) ontology.getAxiomCount(AxiomType.EQUIVALENT_CLASSES));
        metadata.setDisjointClassesAxiomCount((int) ontology.getAxiomCount(AxiomType.DISJOINT_CLASSES));
        metadata.setObjectPropertyDomainCount((int) ontology.getAxiomCount(AxiomType.OBJECT_PROPERTY_DOMAIN));
        metadata.setObjectPropertyRangeCount((int) ontology.getAxiomCount(AxiomType.OBJECT_PROPERTY_RANGE));
        metadata.setDataPropertyDomainCount((int) ontology.getAxiomCount(AxiomType.DATA_PROPERTY_DOMAIN));
        metadata.setDataPropertyRangeCount((int) ontology.getAxiomCount(AxiomType.DATA_PROPERTY_RANGE));
        return metadata;
    }
    private List<ClassInfo> extractClassInformation(Set<OWLClass> classes, OWLOntology ontology, OWLReasoner reasoner) {
        return classes.stream().filter(owlClass -> !owlClass.isBuiltIn()).map(owlClass -> {
            ClassInfo classInfo = new ClassInfo();
            classInfo.setIri(owlClass.getIRI().toString());
            classInfo.setLocalName(owlClass.getIRI().getShortForm());
            classInfo.setAnnotations(extractAnnotations(owlClass, ontology));
            classInfo.setSuperClasses(reasoner.getSuperClasses(owlClass, true).getFlattened().stream().filter(cls -> !cls.isBuiltIn()).map(cls -> cls.getIRI().toString()).collect(Collectors.toList()));
            classInfo.setSubClasses(reasoner.getSubClasses(owlClass, true).getFlattened().stream().filter(cls -> !cls.isBuiltIn()).map(cls -> cls.getIRI().toString()).collect(Collectors.toList()));
            classInfo.setEquivalentClasses(reasoner.getEquivalentClasses(owlClass).getEntities().stream().filter(cls -> !cls.equals(owlClass) && !cls.isBuiltIn()).map(cls -> cls.getIRI().toString()).collect(Collectors.toList()));
            classInfo.setDisjointClasses(reasoner.getDisjointClasses(owlClass).getFlattened().stream().filter(cls -> !cls.isBuiltIn()).map(cls -> cls.getIRI().toString()).collect(Collectors.toList()));
            classInfo.setInstances(reasoner.getInstances(owlClass, false).getFlattened().stream().map(ind -> ind.getIRI().toString()).collect(Collectors.toList()));
            return classInfo;
        }).collect(Collectors.toList());
    }
    private List<IndividualInfo> extractIndividualInformation(Set<OWLNamedIndividual> individuals, OWLOntology ontology, OWLReasoner reasoner) {
        return individuals.stream().map(individual -> {
            IndividualInfo info = new IndividualInfo();
            info.setIri(individual.getIRI().toString());
            info.setLocalName(individual.getIRI().getShortForm());
            info.setAnnotations(extractAnnotations(individual, ontology));
            info.setTypes(reasoner.getTypes(individual, true).getFlattened().stream().filter(cls -> !cls.isBuiltIn()).map(c -> c.getIRI().toString()).collect(Collectors.toList()));
            info.setSameAs(reasoner.getSameIndividuals(individual).getEntities().stream().filter(ind -> !ind.equals(individual)).map(i -> i.getIRI().toString()).collect(Collectors.toList()));
            info.setDifferentFrom(reasoner.getDifferentIndividuals(individual).getFlattened().stream().map(i -> i.getIRI().toString()).collect(Collectors.toList()));
            return info;
        }).collect(Collectors.toList());
    }
    private List<AxiomInfo> extractAxiomInformation(OWLOntology ontology) {
        return ontology.getAxioms().stream().map(axiom -> {
            AxiomInfo axiomInfo = new AxiomInfo();
            axiomInfo.setType(axiom.getAxiomType().getName());
            axiomInfo.setAxiom(axiom.toString());
            Map<String, String> annotations = new HashMap<>();
            for (OWLAnnotation annotation : axiom.getAnnotations()) {
                String key = annotation.getProperty().getIRI().getShortForm();
                String value = extractAnnotationValue(annotation.getValue());
                annotations.put(key, value);
            }
            axiomInfo.setAnnotations(annotations);
            return axiomInfo;
        }).collect(Collectors.toList());
    }
    private List<AnnotationPropertyInfo> extractAnnotationPropertyInformation(Set<OWLAnnotationProperty> properties, OWLOntology ontology) {
        return properties.stream().filter(property -> !property.isBuiltIn()).map(property -> {
            AnnotationPropertyInfo info = new AnnotationPropertyInfo();
            info.setIri(property.getIRI().toString());
            info.setLocalName(property.getIRI().getShortForm());
            info.setAnnotations(extractAnnotations(property, ontology));
            return info;
        }).collect(Collectors.toList());
    }
    private List<DatatypeInfo> extractDatatypeInformation(Set<OWLDatatype> datatypes, OWLOntology ontology) {
        return datatypes.stream().filter(datatype -> !datatype.isBuiltIn()).map(datatype -> {
            DatatypeInfo info = new DatatypeInfo();
            info.setIri(datatype.getIRI().toString());
            info.setLocalName(datatype.getIRI().getShortForm());
            info.setAnnotations(extractAnnotations(datatype, ontology));
            return info;
        }).collect(Collectors.toList());
    }
    private Map<String, String> extractAnnotations(OWLEntity entity, OWLOntology ontology) {
        Map<String, String> annotations = new HashMap<>();
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(entity.getIRI())) {
            String key = axiom.getProperty().getIRI().getShortForm();
            String value = extractAnnotationValue(axiom.getValue());
            annotations.put(key, value);
        }
        return annotations;
    }
    private String extractAnnotationValue(OWLAnnotationValue value) {
        if (value.isLiteral()) {
            return value.asLiteral().get().getLiteral();
        } else if (value.isIRI()) {
            return value.asIRI().get().toString();
        }
        return value.toString();
    }
    private void indexOntologyData(String projectId, OntologyParseResult result) {
        logger.info("Indexing ontology data for project: {}", projectId);
        ontologyIndexService.indexOntologyData(projectId, result);
    }
    private void updateProjectStatus(String projectId, String status, String message) {
        try {
            Query query = new Query(Criteria.where("_id").is(projectId));
            Update update = new Update().set("status", status).set("statusMessage", message).set("lastUpdated", new Date());
            mongoTemplate.updateFirst(query, update, "projects");
            logger.info("Updated project {} status to: {}", projectId, status);
        } catch (Exception e) {
            logger.error("Failed to update project status for project: {}", projectId, e);
        }
    }
    public static class OntologyParseResult {
        private final OntologyMetadata metadata; private final List<ClassInfo> classes; private final List<PropertyInfo> properties; private final List<IndividualInfo> individuals; private final List<AxiomInfo> axioms; private final List<AnnotationPropertyInfo> annotationProperties; private final List<DatatypeInfo> datatypes;
        public OntologyParseResult(OntologyMetadata m, List<ClassInfo> c, List<PropertyInfo> p, List<IndividualInfo> i, List<AxiomInfo> a, List<AnnotationPropertyInfo> ap, List<DatatypeInfo> d) { this.metadata = m; this.classes = c; this.properties = p; this.individuals = i; this.axioms = a; this.annotationProperties = ap; this.datatypes = d; }
        public OntologyMetadata getMetadata() { return metadata; } public List<ClassInfo> getClasses() { return classes; } public List<PropertyInfo> getProperties() { return properties; } public List<IndividualInfo> getIndividuals() { return individuals; } public List<AxiomInfo> getAxioms() { return axioms; } public List<AnnotationPropertyInfo> getAnnotationProperties() { return annotationProperties; } public List<DatatypeInfo> getDatatypes() { return datatypes; }
    }
    public static class OntologyMetadata {
        private String filename, ontologyIRI, versionIRI; private int classCount, objectPropertyCount, dataPropertyCount, individualCount, axiomCount, logicalAxiomCount, declarationAxiomsCount, subClassOfAxiomCount, equivalentClassesAxiomCount, disjointClassesAxiomCount, gciCount, hiddenGciCount, subObjectPropertyOfCount, equivalentObjectPropertiesCount, inverseObjectPropertiesCount, objectPropertyDomainCount, objectPropertyRangeCount, functionalObjectPropertyCount, inverseFunctionalObjectPropertyCount, transitiveObjectPropertyCount, symmetricObjectPropertyCount, asymmetricObjectPropertyCount, reflexiveObjectPropertyCount, irreflexiveObjectPropertyCount, subDataPropertyOfCount, equivalentDataPropertiesCount, dataPropertyDomainCount, dataPropertyRangeCount, functionalDataPropertyCount; private List<String> imports, rootTerms; private Map<String, String> annotations;
        public String getFilename() { return filename; } public void setFilename(String f) { filename = f; } public String getOntologyIRI() { return ontologyIRI; } public void setOntologyIRI(String o) { ontologyIRI = o; } public String getVersionIRI() { return versionIRI; } public void setVersionIRI(String v) { versionIRI = v; } public int getClassCount() { return classCount; } public void setClassCount(int c) { classCount = c; } public int getObjectPropertyCount() { return objectPropertyCount; } public void setObjectPropertyCount(int o) { objectPropertyCount = o; } public int getDataPropertyCount() { return dataPropertyCount; } public void setDataPropertyCount(int d) { dataPropertyCount = d; } public int getIndividualCount() { return individualCount; } public void setIndividualCount(int i) { individualCount = i; } public int getAxiomCount() { return axiomCount; } public void setAxiomCount(int a) { axiomCount = a; } public List<String> getImports() { return imports; } public void setImports(List<String> i) { imports = i; } public Map<String, String> getAnnotations() { return annotations; } public void setAnnotations(Map<String, String> a) { annotations = a; } public int getLogicalAxiomCount() { return logicalAxiomCount; } public void setLogicalAxiomCount(int l) { logicalAxiomCount = l; } public int getGciCount() { return gciCount; } public void setGciCount(int g) { gciCount = g; } public int getSubClassOfAxiomCount() { return subClassOfAxiomCount; } public void setSubClassOfAxiomCount(int s) { subClassOfAxiomCount = s; } public int getEquivalentClassesAxiomCount() { return equivalentClassesAxiomCount; } public void setEquivalentClassesAxiomCount(int e) { equivalentClassesAxiomCount = e; } public int getDisjointClassesAxiomCount() { return disjointClassesAxiomCount; } public void setDisjointClassesAxiomCount(int d) { disjointClassesAxiomCount = d; } public int getObjectPropertyDomainCount() { return objectPropertyDomainCount; } public void setObjectPropertyDomainCount(int o) { objectPropertyDomainCount = o; } public int getObjectPropertyRangeCount() { return objectPropertyRangeCount; } public void setObjectPropertyRangeCount(int o) { objectPropertyRangeCount = o; } public int getDataPropertyDomainCount() { return dataPropertyDomainCount; } public void setDataPropertyDomainCount(int d) { dataPropertyDomainCount = d; } public int getDataPropertyRangeCount() { return dataPropertyRangeCount; } public void setDataPropertyRangeCount(int d) { dataPropertyRangeCount = d; }
    }
    public static class ClassInfo {
        private String iri, localName; private Map<String, String> annotations; private List<String> superClasses, subClasses, equivalentClasses, disjointClasses, instances;
        public String getIri() { return iri; } public void setIri(String i) { iri = i; } public String getLocalName() { return localName; } public void setLocalName(String l) { localName = l; } public Map<String, String> getAnnotations() { return annotations; } public void setAnnotations(Map<String, String> a) { annotations = a; } public List<String> getSuperClasses() { return superClasses; } public void setSuperClasses(List<String> s) { superClasses = s; } public List<String> getSubClasses() { return subClasses; } public void setSubClasses(List<String> s) { subClasses = s; } public List<String> getEquivalentClasses() { return equivalentClasses; } public void setEquivalentClasses(List<String> e) { equivalentClasses = e; } public List<String> getDisjointClasses() { return disjointClasses; } public void setDisjointClasses(List<String> d) { disjointClasses = d; } public List<String> getInstances() { return instances; } public void setInstances(List<String> i) { instances = i; }
    }
    public static class PropertyInfo {
        private String iri, localName, type; private Map<String, String> annotations; private List<String> domains, ranges, characteristics, superProperties, subProperties;
        public String getIri() { return iri; } public void setIri(String i) { iri = i; } public String getLocalName() { return localName; } public void setLocalName(String l) { localName = l; } public String getType() { return type; } public void setType(String t) { type = t; } public Map<String, String> getAnnotations() { return annotations; } public void setAnnotations(Map<String, String> a) { annotations = a; } public List<String> getDomains() { return domains; } public void setDomains(List<String> d) { domains = d; } public List<String> getRanges() { return ranges; } public void setRanges(List<String> r) { ranges = r; } public List<String> getCharacteristics() { return characteristics; } public void setCharacteristics(List<String> c) { characteristics = c; } public List<String> getSuperProperties() { return superProperties; } public void setSuperProperties(List<String> s) { superProperties = s; } public List<String> getSubProperties() { return subProperties; } public void setSubProperties(List<String> s) { subProperties = s; }
    }
    public static class IndividualInfo {
        private String iri, localName; private Map<String, String> annotations; private List<String> types, sameAs, differentFrom;
        public String getIri() { return iri; } public void setIri(String i) { iri = i; } public String getLocalName() { return localName; } public void setLocalName(String l) { localName = l; } public Map<String, String> getAnnotations() { return annotations; } public void setAnnotations(Map<String, String> a) { annotations = a; } public List<String> getTypes() { return types; } public void setTypes(List<String> t) { types = t; } public List<String> getSameAs() { return sameAs; } public void setSameAs(List<String> s) { sameAs = s; } public List<String> getDifferentFrom() { return differentFrom; } public void setDifferentFrom(List<String> d) { differentFrom = d; }
    }
    public static class AxiomInfo {
        private String type, axiom; private Map<String, String> annotations;
        public String getType() { return type; } public void setType(String t) { type = t; } public String getAxiom() { return axiom; } public void setAxiom(String a) { axiom = a; } public Map<String, String> getAnnotations() { return annotations; } public void setAnnotations(Map<String, String> a) { annotations = a; }
    }
    public static class AnnotationPropertyInfo {
        private String iri, localName; private Map<String, String> annotations;
        public String getIri() { return iri; } public void setIri(String i) { iri = i; } public String getLocalName() { return localName; } public void setLocalName(String l) { localName = l; } public Map<String, String> getAnnotations() { return annotations; } public void setAnnotations(Map<String, String> a) { annotations = a; }
    }
    public static class DatatypeInfo {
        private String iri, localName; private Map<String, String> annotations;
        public String getIri() { return iri; } public void setIri(String i) { iri = i; } public String getLocalName() { return localName; } public void setLocalName(String l) { localName = l; } public Map<String, String> getAnnotations() { return annotations; } public void setAnnotations(Map<String, String> a) { annotations = a; }
    }
}