package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import com.mongodb.client.result.UpdateResult;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.controller.ProjectLoadController.TreeNode;
import self.research.ontology.owlEditor.document.*;
import self.research.ontology.owlEditor.dto.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class OntologyIndexService {

    private static final Logger logger = LoggerFactory.getLogger(OntologyIndexService.class);

    @Autowired
    private MongoTemplate mongoTemplate;

    public void indexOntologyData(String projectId, OwlParsingService.OntologyParseResult result) {
        logger.info("Starting indexing process for project: {}", projectId);
        logger.info("Data received from parser: {} Classes, {} Properties, {} Annotation Properties, {} Individuals, {} Axioms",
                result.getClasses().size(),
                result.getProperties().size(),
                result.getAnnotationProperties().size(),
                result.getIndividuals().size(),
                result.getAxioms().size());
                
        try {
            OntologyDocument ontologyDoc = createOntologyDocument(projectId, result);
            saveOntologyMetadata(projectId, ontologyDoc);
            indexClasses(projectId, result.getClasses());
            indexProperties(projectId, result.getProperties());
            indexAnnotationProperties(projectId, result.getAnnotationProperties());
            indexDatatypes(projectId, result.getDatatypes());
            indexIndividuals(projectId, result.getIndividuals());
            indexAxioms(projectId, result.getAxioms());
            createSearchIndexes(projectId, result);
            logger.info("Successfully indexed ontology data for project: {}", projectId);
        } catch (Exception e) {
            logger.error("Failed to index ontology data for project: {}", projectId, e);
            throw new RuntimeException("Failed to index ontology data", e);
        }
    }

    private void indexAnnotationProperties(String projectId, List<OwlParsingService.AnnotationPropertyInfo> properties) {
        logger.info("Indexing {} annotation properties for project: {}", properties.size(), projectId);
        if (properties.isEmpty()) {
            logger.warn("No annotation properties to index for project: {}", projectId);
            return;
        }
        
        Query deleteQuery = new Query(Criteria.where("projectId").is(projectId));
        mongoTemplate.remove(deleteQuery, "ontology_annotation_properties");

        List<AnnotationPropertyDocument> propertyDocuments = new ArrayList<>();
        for (OwlParsingService.AnnotationPropertyInfo propertyInfo : properties) {
            AnnotationPropertyDocument propertyDoc = new AnnotationPropertyDocument();
            propertyDoc.setProjectId(projectId);
            propertyDoc.setIri(propertyInfo.getIri());
            propertyDoc.setLocalName(propertyInfo.getLocalName());
            propertyDoc.setAnnotations(propertyInfo.getAnnotations());
            propertyDoc.setCreatedAt(new Date());
            propertyDoc.setSearchText(buildSearchText(propertyInfo.getLocalName(), propertyInfo.getAnnotations()));
            propertyDocuments.add(propertyDoc);
        }
        mongoTemplate.insert(propertyDocuments, "ontology_annotation_properties");
        logger.info("Successfully inserted {} annotation property documents.", propertyDocuments.size());
    }

    private void indexDatatypes(String projectId, List<OwlParsingService.DatatypeInfo> datatypes) {
        logger.info("Indexing {} datatypes for project: {}", datatypes.size(), projectId);
        Query deleteQuery = new Query(Criteria.where("projectId").is(projectId));
        mongoTemplate.remove(deleteQuery, "ontology_datatypes");

        if (datatypes.isEmpty()) return;

        List<DatatypeDocument> datatypeDocuments = new ArrayList<>();
        for (OwlParsingService.DatatypeInfo datatypeInfo : datatypes) {
            DatatypeDocument datatypeDoc = new DatatypeDocument();
            datatypeDoc.setProjectId(projectId);
            datatypeDoc.setIri(datatypeInfo.getIri());
            datatypeDoc.setLocalName(datatypeInfo.getLocalName());
            datatypeDoc.setAnnotations(datatypeInfo.getAnnotations());
            datatypeDoc.setCreatedAt(new Date());
            datatypeDoc.setSearchText(buildSearchText(datatypeInfo.getLocalName(), datatypeInfo.getAnnotations()));
            datatypeDocuments.add(datatypeDoc);
        }
        mongoTemplate.insert(datatypeDocuments, "ontology_datatypes");
    }

    public List<AnnotationPropertyDto> getAllAnnotationProperties(String projectId) {
        logger.info("Fetching all annotation properties for project: {}", projectId);
        Query query = new Query(Criteria.where("projectId").is(projectId));
        List<AnnotationPropertyDocument> properties = mongoTemplate.find(
                query,
                AnnotationPropertyDocument.class,
                "ontology_annotation_properties"
        );
        return properties.stream()
                .map(this::convertToAnnotationPropertyDto)
                .collect(Collectors.toList());
    }

    public List<DatatypeDto> getAllDatatypes(String projectId) {
        logger.info("Fetching all datatypes for project: {}", projectId);
        Query query = new Query(Criteria.where("projectId").is(projectId));
        List<DatatypeDocument> datatypes = mongoTemplate.find(
                query,
                DatatypeDocument.class,
                "ontology_datatypes"
        );
        return datatypes.stream()
                .map(this::convertToDatatypeDto)
                .collect(Collectors.toList());
    }

    private AnnotationPropertyDto convertToAnnotationPropertyDto(AnnotationPropertyDocument prop) {
        AnnotationPropertyDto dto = new AnnotationPropertyDto();
        dto.setId(prop.getIri());
        dto.setIri(prop.getIri());
        String label = (prop.getAnnotations() != null && prop.getAnnotations().get("label") != null)
                ? cleanLabel(prop.getAnnotations().get("label"))
                : prop.getLocalName();
        dto.setLabel(label);
        dto.setAnnotations(prop.getAnnotations());
        return dto;
    }

    private DatatypeDto convertToDatatypeDto(DatatypeDocument dt) {
        DatatypeDto dto = new DatatypeDto();
        dto.setId(dt.getIri());
        dto.setIri(dt.getIri());
        String label = (dt.getAnnotations() != null && dt.getAnnotations().get("label") != null)
                ? cleanLabel(dt.getAnnotations().get("label"))
                : dt.getLocalName();
        dto.setLabel(label);
        dto.setAnnotations(dt.getAnnotations());
        return dto;
    }

    private OntologyDocument createOntologyDocument(String projectId, OwlParsingService.OntologyParseResult result) {
        OntologyDocument doc = new OntologyDocument();
        doc.setProjectId(projectId);
        doc.setMetadata(result.getMetadata());
        doc.setCreatedAt(new Date());
        doc.setUpdatedAt(new Date());

        Map<String, Integer> statistics = new HashMap<>();
        statistics.put("totalClasses", result.getClasses().size());
        statistics.put("totalProperties", result.getProperties().size());
        statistics.put("totalIndividuals", result.getIndividuals().size());
        statistics.put("totalAxioms", result.getAxioms().size());
        statistics.put("objectProperties", (int) result.getProperties().stream()
                .filter(p -> "ObjectProperty".equals(p.getType())).count());
        statistics.put("dataProperties", (int) result.getProperties().stream()
                .filter(p -> "DataProperty".equals(p.getType())).count());
        doc.setStatistics(statistics);

        return doc;
    }

    private void saveOntologyMetadata(String projectId, OntologyDocument ontologyDoc) {
        logger.info("Saving ontology metadata for project: {}", projectId);
        Query deleteQuery = new Query(Criteria.where("projectId").is(projectId));
        mongoTemplate.remove(deleteQuery, "ontologies");
        mongoTemplate.save(ontologyDoc, "ontologies");
    }

    private void indexClasses(String projectId, List<OwlParsingService.ClassInfo> classes) {
        logger.info("Indexing {} classes for project: {}", classes.size(), projectId);
        if (classes.isEmpty()) {
             logger.warn("No classes to index for project: {}", projectId);
            return;
        }
        
        Query deleteQuery = new Query(Criteria.where("projectId").is(projectId));
        mongoTemplate.remove(deleteQuery, "ontology_classes");
        
        List<ClassDocument> classDocuments = new ArrayList<>();
        for (OwlParsingService.ClassInfo classInfo : classes) {
            ClassDocument classDoc = new ClassDocument();
            classDoc.setProjectId(projectId);
            classDoc.setIri(classInfo.getIri());
            classDoc.setLocalName(classInfo.getLocalName());
            classDoc.setAnnotations(classInfo.getAnnotations());
            classDoc.setSuperClasses(classInfo.getSuperClasses());
            classDoc.setSubClasses(classInfo.getSubClasses());
            classDoc.setEquivalentClasses(classInfo.getEquivalentClasses());
            classDoc.setDisjointClasses(classInfo.getDisjointClasses());
            classDoc.setInstances(classInfo.getInstances());
            classDoc.setCreatedAt(new Date());
            classDoc.setSearchText(buildSearchText(classInfo.getLocalName(), classInfo.getAnnotations()));
            classDocuments.add(classDoc);
        }
        mongoTemplate.insert(classDocuments, "ontology_classes");
        logger.info("Successfully inserted {} class documents.", classDocuments.size());
    }

    private void indexProperties(String projectId, List<OwlParsingService.PropertyInfo> properties) {
        logger.info("Indexing {} properties for project: {}", properties.size(), projectId);
        Query deleteQuery = new Query(Criteria.where("projectId").is(projectId));
        mongoTemplate.remove(deleteQuery, "ontology_properties");
        if (properties.isEmpty()) return;

        List<PropertyDocument> propertyDocuments = new ArrayList<>();
        for (OwlParsingService.PropertyInfo propertyInfo : properties) {
            PropertyDocument propertyDoc = new PropertyDocument();
            propertyDoc.setProjectId(projectId);
            propertyDoc.setIri(propertyInfo.getIri());
            propertyDoc.setLocalName(propertyInfo.getLocalName());
            propertyDoc.setType(propertyInfo.getType());
            propertyDoc.setAnnotations(propertyInfo.getAnnotations());
            propertyDoc.setDomains(propertyInfo.getDomains());
            propertyDoc.setRanges(propertyInfo.getRanges());
            propertyDoc.setCharacteristics(propertyInfo.getCharacteristics());
            propertyDoc.setSuperProperties(propertyInfo.getSuperProperties());
            propertyDoc.setSubProperties(propertyInfo.getSubProperties());
            propertyDoc.setCreatedAt(new Date());
            propertyDoc.setSearchText(buildSearchText(propertyInfo.getLocalName(), propertyInfo.getAnnotations()));
            propertyDocuments.add(propertyDoc);
        }
        mongoTemplate.insert(propertyDocuments, "ontology_properties");
    }

    private void indexIndividuals(String projectId, List<OwlParsingService.IndividualInfo> individuals) {
        logger.info("Indexing {} individuals for project: {}", individuals.size(), projectId);
        Query deleteQuery = new Query(Criteria.where("projectId").is(projectId));
        mongoTemplate.remove(deleteQuery, "ontology_individuals");
        if (individuals.isEmpty()) return;

        List<IndividualDocument> individualDocuments = new ArrayList<>();
        for (OwlParsingService.IndividualInfo individualInfo : individuals) {
            IndividualDocument individualDoc = new IndividualDocument();
            individualDoc.setProjectId(projectId);
            individualDoc.setIri(individualInfo.getIri());
            individualDoc.setLocalName(individualInfo.getLocalName());
            individualDoc.setAnnotations(individualInfo.getAnnotations());
            individualDoc.setTypes(individualInfo.getTypes());
            individualDoc.setSameAs(individualInfo.getSameAs());
            individualDoc.setDifferentFrom(individualInfo.getDifferentFrom());
            individualDoc.setCreatedAt(new Date());
            individualDoc.setSearchText(buildSearchText(individualInfo.getLocalName(), individualInfo.getAnnotations()));
            individualDocuments.add(individualDoc);
        }
        mongoTemplate.insert(individualDocuments, "ontology_individuals");
    }

    private void indexAxioms(String projectId, List<OwlParsingService.AxiomInfo> axioms) {
        logger.info("Indexing {} axioms for project: {}", axioms.size(), projectId);
        Query deleteQuery = new Query(Criteria.where("projectId").is(projectId));
        mongoTemplate.remove(deleteQuery, "ontology_axioms");
        if (axioms.isEmpty()) return;

        int batchSize = 1000;
        for (int i = 0; i < axioms.size(); i += batchSize) {
            List<OwlParsingService.AxiomInfo> batch = axioms.subList(i, Math.min(i + batchSize, axioms.size()));
            List<AxiomDocument> axiomDocuments = batch.stream().map(axiomInfo -> {
                AxiomDocument axiomDoc = new AxiomDocument();
                axiomDoc.setProjectId(projectId);
                axiomDoc.setType(axiomInfo.getType());
                axiomDoc.setAxiom(axiomInfo.getAxiom());
                axiomDoc.setAnnotations(axiomInfo.getAnnotations());
                axiomDoc.setCreatedAt(new Date());
                axiomDoc.setSearchText(buildSearchText(axiomInfo.getType(), axiomInfo.getAnnotations()));
                return axiomDoc;
            }).collect(Collectors.toList());
            mongoTemplate.insert(axiomDocuments, "ontology_axioms");
        }
    }

    private void createSearchIndexes(String projectId, OwlParsingService.OntologyParseResult result) {
        logger.info("Creating search indexes for project: {}", projectId);
        SearchIndexDocument searchIndex = new SearchIndexDocument();
        searchIndex.setProjectId(projectId);
        searchIndex.setCreatedAt(new Date());

        Map<String, Object> searchData = new HashMap<>();
        searchData.put("classNames", result.getClasses().stream().map(OwlParsingService.ClassInfo::getLocalName).collect(Collectors.toList()));
        searchData.put("propertyNames", result.getProperties().stream().map(OwlParsingService.PropertyInfo::getLocalName).collect(Collectors.toList()));
        searchData.put("individualNames", result.getIndividuals().stream().map(OwlParsingService.IndividualInfo::getLocalName).collect(Collectors.toList()));

        Set<String> allAnnotations = new HashSet<>();
        result.getClasses().forEach(c -> allAnnotations.addAll(c.getAnnotations().values()));
        result.getProperties().forEach(p -> allAnnotations.addAll(p.getAnnotations().values()));
        result.getIndividuals().forEach(i -> allAnnotations.addAll(i.getAnnotations().values()));
        searchData.put("annotations", new ArrayList<>(allAnnotations));
        searchIndex.setSearchData(searchData);

        Query deleteQuery = new Query(Criteria.where("projectId").is(projectId));
        mongoTemplate.remove(deleteQuery, "ontology_search_index");
        mongoTemplate.save(searchIndex, "ontology_search_index");
    }

    public boolean updateAnnotationProperty(String projectId, AnnotationPropertyDto annotationPropertyDto) {
        Query query = new Query(Criteria.where("projectId").is(projectId).and("iri").is(annotationPropertyDto.getIri()));
        Update update = new Update();
        if (annotationPropertyDto.getLabel() != null) {
            update.set("annotations.label", annotationPropertyDto.getLabel());
        }
        if (annotationPropertyDto.getAnnotations() != null) {
            for (Map.Entry<String, String> entry : annotationPropertyDto.getAnnotations().entrySet()) {
                update.set("annotations." + entry.getKey(), entry.getValue());
            }
        }
        UpdateResult result = mongoTemplate.updateFirst(query, update, "ontology_annotation_properties");
        return result.getModifiedCount() > 0;
    }

    private String buildSearchText(String localName, Map<String, String> annotations) {
        StringBuilder searchText = new StringBuilder();
        if (localName != null) {
            searchText.append(localName).append(" ");
        }
        if (annotations != null) {
            annotations.values().forEach(value -> {
                if (value != null) {
                    searchText.append(value).append(" ");
                }
            });
        }
        return searchText.toString().trim().toLowerCase();
    }

    public List<ClassDocument> searchClasses(String projectId, String query) {
        Query searchQuery = new Query(Criteria.where("projectId").is(projectId)
                .and("searchText").regex(".*" + query.toLowerCase() + ".*"));
        return mongoTemplate.find(searchQuery, ClassDocument.class, "ontology_classes");
    }

    public List<TreeNode> searchClasses2(String projectId, String query) {
        logger.info("Searching for classes in project: {} with query: {}", projectId, query);
        try {
            String regexQuery = ".*" + query.toLowerCase() + ".*";
            Criteria searchCriteria = new Criteria().orOperator(
                    Criteria.where("annotations.label").regex(regexQuery, "i"),
                    Criteria.where("localName").regex(regexQuery, "i")
            );
            Query searchQuery = new Query(Criteria.where("projectId").is(projectId).andOperator(searchCriteria));
            List<ClassDocument> matchingClasses = mongoTemplate.find(searchQuery, ClassDocument.class, "ontology_classes");

            if (matchingClasses.isEmpty()) {
                logger.warn("No classes found for query: {} in project: {}", query, projectId);
                return new ArrayList<>();
            }
            return matchingClasses.stream().map(classDoc -> {
                Map<String, String> annotations = classDoc.getAnnotations();
                String label = (annotations != null && annotations.get("label") != null)
                        ? annotations.get("label").replaceAll("\"|\\^\\^xsd:string", "").trim()
                        : classDoc.getLocalName();
                
                Query hasChildrenQuery = new Query(Criteria.where("projectId").is(projectId)
                        .and("superClasses").in(classDoc.getIri()));
                boolean hasChildren = mongoTemplate.exists(hasChildrenQuery, ClassDocument.class, "ontology_classes");
                
                return new TreeNode(classDoc.getIri(), label, hasChildren ? new ArrayList<>() : null, annotations, hasChildren);
            }).collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error searching classes for project: {} with query: {}", projectId, query, e);
            return new ArrayList<>();
        }
    }

    public List<PropertyDto> searchProperties(String projectId, String query) {
        Query searchQuery = new Query(Criteria.where("projectId").is(projectId)
                .and("searchText").regex(".*" + query.toLowerCase() + ".*"));
        List<PropertyDocument> properties = mongoTemplate.find(searchQuery, PropertyDocument.class, "ontology_properties");
        return properties.stream().map(this::convertToPropertyDto).collect(Collectors.toList());
    }

    public List<IndividualDto> searchIndividuals(String projectId, String query) {
        Query searchQuery = new Query(Criteria.where("projectId").is(projectId)
                .and("searchText").regex(".*" + query.toLowerCase() + ".*"));
        List<IndividualDocument> individuals = mongoTemplate.find(searchQuery, IndividualDocument.class, "ontology_individuals");
        return individuals.stream().map(this::convertToIndividualDto).collect(Collectors.toList());
    }

    public List<PropertyDto> getObjectPropertyHierarchy(String projectId) {
        logger.info("Building object property hierarchy for project: {}", projectId);
        try {
            Query query = new Query(Criteria.where("projectId").is(projectId).and("type").is("ObjectProperty"));
            List<PropertyDocument> allProperties = mongoTemplate.find(query, PropertyDocument.class, "ontology_properties");

            if (allProperties.isEmpty()) {
                logger.warn("No object properties found for project: {}", projectId);
                return new ArrayList<>();
            }

            Map<String, PropertyDto> propertyMap = new HashMap<>();
            for (PropertyDocument propDoc : allProperties) {
                PropertyDto dto = convertToPropertyDto(propDoc);
                dto.setChildren(new ArrayList<>());
                propertyMap.put(propDoc.getIri(), dto);
            }

            List<PropertyDto> rootProperties = new ArrayList<>();
            final String topObjectPropertyIRI = "http://www.w3.org/2002/07/owl#topObjectProperty";

            for (PropertyDocument propDoc : allProperties) {
                List<String> superProperties = propDoc.getSuperProperties();
                boolean hasParentInOntology = false;

                if (superProperties != null && !superProperties.isEmpty()) {
                    for (String parentIri : superProperties) {
                        PropertyDto parentDto = propertyMap.get(parentIri);
                        if (parentDto != null) {
                            PropertyDto childDto = propertyMap.get(propDoc.getIri());
                            if (childDto != null && !parentDto.getChildren().contains(childDto)) {
                                parentDto.getChildren().add(childDto);
                            }
                            hasParentInOntology = true;
                        }
                    }
                }

                if (!hasParentInOntology) {
                    rootProperties.add(propertyMap.get(propDoc.getIri()));
                }
            }
            
            if (rootProperties.isEmpty() && !allProperties.isEmpty()) {
                 logger.info("No root object properties found (which may be correct). Returning empty list.");
                 return new ArrayList<>();
            }

            if (rootProperties.size() > 1) {
                PropertyDto topProperty = new PropertyDto();
                topProperty.setId(topObjectPropertyIRI);
                topProperty.setIri(topObjectPropertyIRI);
                topProperty.setLabel("owl:topObjectProperty");
                topProperty.setType("ObjectProperty");
                topProperty.setChildren(rootProperties);
                return Collections.singletonList(topProperty);
            }

            logger.info("Built object property hierarchy for project: {} with {} root properties", projectId, rootProperties.size());
            return rootProperties;

        } catch (Exception e) {
            logger.error("Error building object property hierarchy for project: {}", projectId, e);
            return new ArrayList<>();
        }
    }

    public Map<String, Object> getTopLevelClassesPaginated(String projectId, int page, int size, String search) {
        logger.info("Getting paginated top-level classes for project: {}, page: {}, size: {}", projectId, page, size);

        Query query = new Query(Criteria.where("projectId").is(projectId));
        List<ClassDocument> allClasses = mongoTemplate.find(query, ClassDocument.class, "ontology_classes");

        if (allClasses.isEmpty()) {
            Map<String, Object> emptyResponse = new HashMap<>();
            emptyResponse.put("classes", new ArrayList<>());
            emptyResponse.put("total", 0);
            emptyResponse.put("page", page);
            emptyResponse.put("size", size);
            emptyResponse.put("hasMore", false);
            return emptyResponse;
        }

        final String owlThingIri = "http://www.w3.org/2002/07/owl#Thing";

        Set<String> allClassIRIs = allClasses.stream()
                .map(ClassDocument::getIri)
                .collect(Collectors.toSet());

        Set<String> classesWithParents = new HashSet<>();
        for (ClassDocument classDoc : allClasses) {
            List<String> superClasses = classDoc.getSuperClasses();
            if (superClasses != null) {
                for (String parentIri : superClasses) {
                    if (!parentIri.equals(owlThingIri) && allClassIRIs.contains(parentIri)) {
                        classesWithParents.add(classDoc.getIri());
                        break;
                    }
                }
            }
        }

        Stream<ClassDocument> topLevelStream = allClasses.stream()
                .filter(c -> !classesWithParents.contains(c.getIri()));

        if (search != null && !search.trim().isEmpty()) {
            String searchLower = search.toLowerCase();
            topLevelStream = topLevelStream.filter(c -> {
                String label = c.getAnnotations() != null && c.getAnnotations().get("label") != null
                        ? cleanLabel(c.getAnnotations().get("label")).toLowerCase()
                        : c.getLocalName().toLowerCase();
                return label.contains(searchLower);
            });
        }

        List<ClassDocument> sortedClasses = topLevelStream
                .sorted((a, b) -> {
                    String labelA = a.getAnnotations() != null && a.getAnnotations().get("label") != null
                            ? cleanLabel(a.getAnnotations().get("label"))
                            : a.getLocalName();
                    String labelB = b.getAnnotations() != null && b.getAnnotations().get("label") != null
                            ? cleanLabel(b.getAnnotations().get("label"))
                            : b.getLocalName();
                    return labelA.compareToIgnoreCase(labelB);
                })
                .collect(Collectors.toList());

        int total = sortedClasses.size();
        int start = page * size;
        int end = Math.min(start + size, total);

        if (start >= total) {
            Map<String, Object> response = new HashMap<>();
            response.put("classes", new ArrayList<>());
            response.put("total", total);
            response.put("page", page);
            response.put("size", size);
            response.put("hasMore", false);
            return response;
        }

        List<ClassDocument> pageClasses = sortedClasses.subList(start, end);

        List<Map<String, Object>> classData = pageClasses.stream()
                .map(c -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", c.getIri());
                    String label = c.getAnnotations() != null && c.getAnnotations().get("label") != null
                            ? cleanLabel(c.getAnnotations().get("label"))
                            : c.getLocalName();
                    item.put("label", label);

                    Query hasChildrenQuery = new Query(Criteria.where("projectId").is(projectId)
                            .and("superClasses").in(c.getIri()));
                    boolean hasChildren = mongoTemplate.exists(hasChildrenQuery, ClassDocument.class, "ontology_classes");
                    
                    item.put("hasChildren", hasChildren); 
                    item.put("annotations", c.getAnnotations());
                    return item;
                })
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("classes", classData);
        response.put("total", total);
        response.put("page", page);
        response.put("size", size);
        response.put("hasMore", end < total);

        logger.info("Returning {} classes (page {} of {})", classData.size(), page, (total + size - 1) / size);

        return response;
    }

    public List<PropertyDto> getDataPropertyHierarchy(String projectId) {
        logger.info("Building data property hierarchy for project: {}", projectId);
        try {
            Query query = new Query(Criteria.where("projectId").is(projectId).and("type").is("DataProperty"));
            List<PropertyDocument> allProperties = mongoTemplate.find(query, PropertyDocument.class, "ontology_properties");

            if (allProperties.isEmpty()) {
                logger.warn("No data properties found for project: {}", projectId);
                return new ArrayList<>();
            }

            Map<String, PropertyDto> propertyMap = new HashMap<>();
            for (PropertyDocument propDoc : allProperties) {
                PropertyDto dto = convertToPropertyDto(propDoc);
                dto.setChildren(new ArrayList<>());
                propertyMap.put(propDoc.getIri(), dto);
            }

            List<PropertyDto> rootProperties = new ArrayList<>();
            final String topDataPropertyIRI = "http://www.w3.org/2002/07/owl#topDataProperty";

            for (PropertyDocument propDoc : allProperties) {
                List<String> superProperties = propDoc.getSuperProperties();
                boolean hasParentInOntology = false;

                if (superProperties != null && !superProperties.isEmpty()) {
                    for (String parentIri : superProperties) {
                        PropertyDto parentDto = propertyMap.get(parentIri);
                        if (parentDto != null) {
                            PropertyDto childDto = propertyMap.get(propDoc.getIri());
                            if (childDto != null && !parentDto.getChildren().contains(childDto)) {
                                parentDto.getChildren().add(childDto);
                            }
                            hasParentInOntology = true;
                        }
                    }
                }

                if (!hasParentInOntology) {
                    rootProperties.add(propertyMap.get(propDoc.getIri()));
                }
            }
            
            if (rootProperties.isEmpty() && !allProperties.isEmpty()) {
                 logger.info("No root data properties found (which may be correct). Returning empty list.");
                 return new ArrayList<>();
            }

            if (rootProperties.size() > 1) {
                PropertyDto topProperty = new PropertyDto();
                topProperty.setId(topDataPropertyIRI);
                topProperty.setIri(topDataPropertyIRI);
                topProperty.setLabel("owl:topDataProperty");
                topProperty.setType("DataProperty");
                topProperty.setChildren(rootProperties);
                return Collections.singletonList(topProperty);
            }

            logger.info("Built data property hierarchy for project: {} with {} root properties", projectId, rootProperties.size());
            return rootProperties;

        } catch (Exception e) {
            logger.error("Error building data property hierarchy for project: {}", projectId, e);
            return new ArrayList<>();
        }
    }

    public OntologyDocument getOntologyMetadata(String projectId) {
        Query query = new Query(Criteria.where("projectId").is(projectId));
        return mongoTemplate.findOne(query, OntologyDocument.class, "ontologies");
    }

    public void deleteOntologyData(String projectId) {
        logger.info("Deleting ontology data for project: {}", projectId);
        Query deleteQuery = new Query(Criteria.where("projectId").is(projectId));
        mongoTemplate.remove(deleteQuery, "ontologies");
        mongoTemplate.remove(deleteQuery, "ontology_classes");
        mongoTemplate.remove(deleteQuery, "ontology_properties");
        mongoTemplate.remove(deleteQuery, "ontology_annotation_properties");
        mongoTemplate.remove(deleteQuery, "ontology_datatypes");
        mongoTemplate.remove(deleteQuery, "ontology_individuals");
        mongoTemplate.remove(deleteQuery, "ontology_axioms");
        mongoTemplate.remove(deleteQuery, "ontology_search_index");
    }

    public List<TreeNode> getClassHierarchy(String projectId) {
        logger.info("Building class hierarchy for project: {}", projectId);
        try {
            Query query = new Query(Criteria.where("projectId").is(projectId));
            List<ClassDocument> allClasses = mongoTemplate.find(query, ClassDocument.class, "ontology_classes");

            logger.info("Found {} classes for project: {}", allClasses.size(), projectId);

            if (allClasses.isEmpty()) {
                return new ArrayList<>();
            }

            final String owlThingIri = "http://www.w3.org/2002/07/owl#Thing";

            Set<String> allClassIRIs = allClasses.stream()
                    .map(ClassDocument::getIri)
                    .collect(Collectors.toSet());

            Set<String> classesWithParents = new HashSet<>();

            for (ClassDocument classDoc : allClasses) {
                List<String> superClasses = classDoc.getSuperClasses();
                if (superClasses != null) {
                    for (String parentIri : superClasses) {
                        if (!parentIri.equals(owlThingIri) && allClassIRIs.contains(parentIri)) {
                            classesWithParents.add(classDoc.getIri());
                            break;
                        }
                    }
                }
            }

            logger.info("Classes with parents: {}", classesWithParents.size());

            TreeNode owlThingNode = new TreeNode(
                    owlThingIri,
                    "owl:Thing",
                    new ArrayList<>(),
                    new HashMap<>(),
                    true
            );

            List<TreeNode> topLevelClasses = new ArrayList<>();
            for (ClassDocument classDoc : allClasses) {
                boolean isTopLevel = classDoc.getSuperClasses() == null || 
                                     classDoc.getSuperClasses().isEmpty() ||
                                     (classDoc.getSuperClasses().size() == 1 && classDoc.getSuperClasses().contains(owlThingIri));

                if (!classesWithParents.contains(classDoc.getIri())) {
                
                    Map<String, String> annotations = classDoc.getAnnotations();
                    String label = (annotations != null && annotations.get("label") != null)
                            ? cleanLabel(annotations.get("label"))
                            : classDoc.getLocalName();

                    Query hasChildrenQuery = new Query(Criteria.where("projectId").is(projectId)
                            .and("superClasses").in(classDoc.getIri()));
                    boolean hasChildren = mongoTemplate.exists(hasChildrenQuery, ClassDocument.class, "ontology_classes");

                    TreeNode node = new TreeNode(
                            classDoc.getIri(),
                            label,
                            hasChildren ? new ArrayList<>() : null,
                            annotations,
                            hasChildren
                    );

                    topLevelClasses.add(node);
                }
            }

            topLevelClasses.sort((a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()));
            
            if (topLevelClasses.size() == 1) {
                 logger.info("Found single root class: {}. Returning it as root.", topLevelClasses.get(0).getLabel());
                 return topLevelClasses;
            }

            owlThingNode.setChildren(topLevelClasses);
            logger.info("Found {} top-level classes. Adding them under owl:Thing.", topLevelClasses.size());
            return Collections.singletonList(owlThingNode);

        } catch (Exception e) {
            logger.error("Error building class hierarchy for project: {}", projectId, e);
            return new ArrayList<>();
        }
    }

    public List<TreeNode> getClassChildren(String projectId, String parentIri) {
        logger.info("Fetching children for parent: {} in project: {}", parentIri, projectId);

        try {
            Query query = new Query(Criteria.where("projectId").is(projectId)
                    .and("superClasses").in(parentIri));

            List<ClassDocument> childClasses = mongoTemplate.find(query, ClassDocument.class, "ontology_classes");

            logger.info("Found {} direct children", childClasses.size());

            List<TreeNode> children = new ArrayList<>();

            for (ClassDocument classDoc : childClasses) {
                Map<String, String> annotations = classDoc.getAnnotations();
                
                String label = (annotations != null && annotations.get("label") != null)
                        ? cleanLabel(annotations.get("label"))
                        : classDoc.getLocalName();

                Query hasChildrenQuery = new Query(Criteria.where("projectId").is(projectId)
                        .and("superClasses").in(classDoc.getIri()));
                boolean hasChildren = mongoTemplate.exists(hasChildrenQuery, ClassDocument.class, "ontology_classes");

                TreeNode node = new TreeNode(
                        classDoc.getIri(),
                        label,
                        hasChildren ? new ArrayList<>() : null,
                        annotations,
                        hasChildren
                );

                children.add(node);
            }

            children.sort((a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()));

            return children;

        } catch (Exception e) {
            logger.error("Error fetching children for parent: {}", parentIri, e);
            return new ArrayList<>();
        }
    }

    public List<PropertyDto> getAllProperties(String projectId) {
        logger.info("Fetching all properties for project: {}", projectId);
        Query query = new Query(Criteria.where("projectId").is(projectId));
        List<PropertyDocument> properties = mongoTemplate.find(query, PropertyDocument.class, "ontology_properties");
        return properties.stream().map(this::convertToPropertyDto).collect(Collectors.toList());
    }

    public List<IndividualDto> getAllIndividuals(String projectId) {
        logger.info("Fetching all individuals for project: {}", projectId);
        Query query = new Query(Criteria.where("projectId").is(projectId));
        List<IndividualDocument> individuals = mongoTemplate.find(query, IndividualDocument.class, "ontology_individuals");
        return individuals.stream().map(this::convertToIndividualDto).collect(Collectors.toList());
    }

    public String createClass(String projectId, String className, String parentIri) {
        logger.info("Creating class '{}' with parent '{}' for project: {}", className, parentIri, projectId);
        try {
            OntologyDocument ontologyDoc = getOntologyMetadata(projectId);
            String baseIri = (ontologyDoc != null && ontologyDoc.getMetadata() != null && ontologyDoc.getMetadata().getOntologyIRI() != null) ? ontologyDoc.getMetadata().getOntologyIRI() : "http://www.example.org/ontology";
            String sanitizedName = className.replaceAll("[^a-zA-Z0-9]", "_");
            String classIri = baseIri + "#" + sanitizedName;

            ClassDocument newClass = new ClassDocument();
            newClass.setProjectId(projectId);
            newClass.setIri(classIri);
            newClass.setLocalName(sanitizedName);
            Map<String, String> annotations = new HashMap<>();
            annotations.put("label", "\"" + className + "\"^^xsd:string");
            newClass.setAnnotations(annotations);
            newClass.setSuperClasses(parentIri != null && !parentIri.isEmpty() ? Collections.singletonList(parentIri) : Collections.singletonList("http://www.w3.org/2002/07/owl#Thing"));
            newClass.setSubClasses(new ArrayList<>());
            newClass.setEquivalentClasses(new ArrayList<>());
            newClass.setDisjointClasses(new ArrayList<>());
            newClass.setInstances(new ArrayList<>());
            newClass.setSearchText(className.toLowerCase());
            newClass.setCreatedAt(new Date());

            mongoTemplate.save(newClass, "ontology_classes");

            if (parentIri != null && !parentIri.isEmpty()) {
                Query parentQuery = new Query(Criteria.where("projectId").is(projectId).and("iri").is(parentIri));
                Update update = new Update().addToSet("subClasses", classIri);
                mongoTemplate.updateFirst(parentQuery, update, ClassDocument.class);
            }
            updateOntologyStatistics(projectId);
            return classIri;
        } catch (Exception e) {
            logger.error("Error creating class for project: {}", projectId, e);
            throw new RuntimeException("Failed to create class", e);
        }
    }

    public void updateClassAnnotations(String projectId, String classIri, Map<String, String> annotations) {
        logger.info("Updating annotations for class '{}' in project: {}", classIri, projectId);
        try {
            Query query = new Query(Criteria.where("projectId").is(projectId).and("iri").is(classIri));
            Update update = new Update().set("annotations", annotations);
            mongoTemplate.updateFirst(query, update, ClassDocument.class);
        } catch (Exception e) {
            logger.error("Error updating class annotations for project: {}", projectId, e);
            throw new RuntimeException("Failed to update class annotations", e);
        }
    }

    public void deleteClass(String projectId, String classIri) {
        logger.info("Deleting class '{}' from project: {}", classIri, projectId);
        try {
            Query findQuery = new Query(Criteria.where("projectId").is(projectId).and("iri").is(classIri));
            ClassDocument classToDelete = mongoTemplate.findOne(findQuery, ClassDocument.class, "ontology_classes");
            if (classToDelete == null) throw new RuntimeException("Class not found: " + classIri);
            if (classToDelete.getSubClasses() != null && !classToDelete.getSubClasses().isEmpty()) {
                throw new RuntimeException("Cannot delete class with subclasses. Please delete children first.");
            }
            if (classToDelete.getSuperClasses() != null) {
                for (String parentIri : classToDelete.getSuperClasses()) {
                    Query parentQuery = new Query(Criteria.where("projectId").is(projectId).and("iri").is(parentIri));
                    Update update = new Update().pull("subClasses", classIri);
                    mongoTemplate.updateFirst(parentQuery, update, ClassDocument.class);
                }
            }
            mongoTemplate.remove(findQuery, "ontology_classes");
            updateOntologyStatistics(projectId);
        } catch (Exception e) {
            logger.error("Error deleting class for project: {}", projectId, e);
            throw new RuntimeException("Failed to delete class", e);
        }
    }

    private void updateOntologyStatistics(String projectId) {
        logger.info("Updating statistics for project: {}", projectId);
        try {
            Query projectQuery = new Query(Criteria.where("projectId").is(projectId));
            long classCount = mongoTemplate.count(projectQuery, "ontology_classes");
            long propertyCount = mongoTemplate.count(projectQuery, "ontology_properties");
            long individualCount = mongoTemplate.count(projectQuery, "ontology_individuals");
            long axiomCount = mongoTemplate.count(projectQuery, "ontology_axioms");
            long objectPropertyCount = mongoTemplate.count(new Query(Criteria.where("projectId").is(projectId).and("type").is("ObjectProperty")), "ontology_properties");
            long dataPropertyCount = propertyCount - objectPropertyCount;

            Map<String, Integer> statistics = new HashMap<>();
            statistics.put("totalClasses", (int) classCount);
            statistics.put("totalProperties", (int) propertyCount);
            statistics.put("totalIndividuals", (int) individualCount);
            statistics.put("totalAxioms", (int) axiomCount);
            statistics.put("objectProperties", (int) objectPropertyCount);
            statistics.put("dataProperties", (int) dataPropertyCount);

            Update update = new Update()
                    .set("statistics", statistics)
                    .set("updatedAt", new Date());

            mongoTemplate.updateFirst(new Query(Criteria.where("projectId").is(projectId)), update, "ontologies");
        } catch (Exception e) {
            logger.error("Error updating statistics for project: {}", projectId, e);
        }
    }

    private PropertyDto convertToPropertyDto(PropertyDocument prop) {
        PropertyDto dto = new PropertyDto();
        dto.setId(prop.getIri());
        dto.setIri(prop.getIri());
        String label = (prop.getAnnotations() != null && prop.getAnnotations().get("label") != null)
                ? cleanLabel(prop.getAnnotations().get("label"))
                : prop.getLocalName();
        dto.setLabel(label);
        dto.setType(prop.getType());
        dto.setAnnotations(prop.getAnnotations());
        dto.setDomains(prop.getDomains());
        dto.setRanges(prop.getRanges());
        dto.setCharacteristics(prop.getCharacteristics());
        dto.setSuperProperties(prop.getSuperProperties());
        dto.setSubProperties(prop.getSubProperties());
        return dto;
    }

    private IndividualDto convertToIndividualDto(IndividualDocument ind) {
        IndividualDto dto = new IndividualDto();
        dto.setId(ind.getIri());
        dto.setIri(ind.getIri());
        String label = (ind.getAnnotations() != null && ind.getAnnotations().get("label") != null) ? cleanLabel(ind.getAnnotations().get("label")) : ind.getLocalName();
        dto.setLabel(label);
        dto.setAnnotations(ind.getAnnotations());
        dto.setTypes(ind.getTypes());
        dto.setSameAs(ind.getSameAs());
        dto.setDifferentFrom(ind.getDifferentFrom());
        return dto;
    }

    private String cleanLabel(String label) {
        if (label == null) return "";
        return label.replaceAll("\"|\\^\\^xsd:string", "").trim();
    }
    
    public UsageInfoDto getClassUsage(String projectId, String classIri) {
        logger.info("Fetching usage information for class: {} in project: {}", classIri, projectId);

        try {
            List<UsageInfoDto.AxiomUsage> usages = new ArrayList<>();

            Query thisClassQuery = new Query(Criteria.where("projectId").is(projectId).and("iri").is(classIri));
            ClassDocument thisClass = mongoTemplate.findOne(thisClassQuery, ClassDocument.class, "ontology_classes");

            if (thisClass == null) {
                logger.warn("Class not found: {} in project: {}", classIri, projectId);
                return new UsageInfoDto(classIri, 0, new ArrayList<>());
            }

            String classShortForm = getShortForm(classIri);

            Query subClassQuery = new Query(Criteria.where("projectId").is(projectId)
                    .and("superClasses").in(classIri));
            List<ClassDocument> subClasses = mongoTemplate.find(subClassQuery, ClassDocument.class, "ontology_classes");

            for (ClassDocument subClass : subClasses) {
                String label = getClassLabel(subClass);
                usages.add(new UsageInfoDto.AxiomUsage(
                        "SubClassOf",
                        label + " SubClassOf " + classShortForm,
                        subClass.getIri(),
                        "SubClassOf"
                ));
            }

            if (thisClass.getSuperClasses() != null) {
                for (String superIri : thisClass.getSuperClasses()) {
                    if (!superIri.equals("http://www.w3.org/2002/07/owl#Thing")) {
                        usages.add(new UsageInfoDto.AxiomUsage(
                                "SubClassOf",
                                getClassLabel(thisClass) + " SubClassOf " + getShortForm(superIri),
                                superIri,
                                "SubClassOf"
                        ));
                    }
                }
            }

            if (thisClass.getEquivalentClasses() != null) {
                for (String equivIri : thisClass.getEquivalentClasses()) {
                    usages.add(new UsageInfoDto.AxiomUsage(
                            "EquivalentTo",
                            getClassLabel(thisClass) + " EquivalentTo " + getShortForm(equivIri),
                            equivIri,
                            "EquivalentClasses"
                    ));
                }
            }

            if (thisClass.getDisjointClasses() != null) {
                for (String disjointIri : thisClass.getDisjointClasses()) {
                    usages.add(new UsageInfoDto.AxiomUsage(
                            "DisjointWith",
                            getClassLabel(thisClass) + " DisjointWith " + getShortForm(disjointIri),
                            disjointIri,
                            "DisjointClasses"
                    ));
                }
            }

            Query domainQuery = new Query(Criteria.where("projectId").is(projectId)
                    .and("domains").is(classIri));
            List<PropertyDocument> propertiesWithDomain = mongoTemplate.find(domainQuery, PropertyDocument.class, "ontology_properties");

            for (PropertyDocument prop : propertiesWithDomain) {
                String propLabel = getPropertyLabel(prop);
                usages.add(new UsageInfoDto.AxiomUsage(
                        "Domain",
                        propLabel + " Domain " + classShortForm,
                        prop.getIri(),
                        "PropertyDomain"
                ));
            }

            Query rangeQuery = new Query(Criteria.where("projectId").is(projectId)
                    .and("ranges").is(classIri));
            List<PropertyDocument> propertiesWithRange = mongoTemplate.find(rangeQuery, PropertyDocument.class, "ontology_properties");

            for (PropertyDocument prop : propertiesWithRange) {
                String propLabel = getPropertyLabel(prop);
                usages.add(new UsageInfoDto.AxiomUsage(
                        "Range",
                        propLabel + " Range " + classShortForm,
                        prop.getIri(),
                        "PropertyRange"
                ));
            }

            Query individualQuery = new Query(Criteria.where("projectId").is(projectId)
                    .and("types").is(classIri));
            List<IndividualDocument> individuals = mongoTemplate.find(individualQuery, IndividualDocument.class, "ontology_individuals");

            for (IndividualDocument ind : individuals) {
                String indLabel = getIndividualLabel(ind);
                usages.add(new UsageInfoDto.AxiomUsage(
                        "Type",
                        indLabel + " Type " + classShortForm,
                        ind.getIri(),
                        "ClassAssertion"
                ));
            }

            Query axiomQuery = new Query(Criteria.where("projectId").is(projectId));
            List<AxiomDocument> allAxioms = mongoTemplate.find(axiomQuery, AxiomDocument.class, "ontology_axioms");

            logger.info("Searching through {} axioms for references to {}", allAxioms.size(), classIri);

            for (AxiomDocument axiomDoc : allAxioms) {
                String axiomText = axiomDoc.getAxiom();
                if (axiomText != null && (axiomText.contains(classIri) || axiomText.contains("<" + classIri + ">"))) {
                    String readableAxiom = parseAxiomForDisplay(axiomText, classIri);
                    String category = categorizeAxiom(axiomDoc.getType(), axiomText);
                    String relatedEntity = extractRelatedEntity(axiomText, classIri);
                    usages.add(new UsageInfoDto.AxiomUsage(
                            category,
                            readableAxiom,
                            relatedEntity,
                            axiomDoc.getType()
                    ));
                }
            }

            List<UsageInfoDto.AxiomUsage> uniqueUsages = usages.stream()
                    .collect(Collectors.toMap(
                            UsageInfoDto.AxiomUsage::getDescription,
                            u -> u,
                            (u1, u2) -> u1
                    ))
                    .values()
                    .stream()
                    .collect(Collectors.toList());

            logger.info("Found {} unique usages for class: {}", uniqueUsages.size(), classIri);
            return new UsageInfoDto(classIri, uniqueUsages.size(), uniqueUsages);

        } catch (Exception e) {
            logger.error("Error fetching usage for class: {}", classIri, e);
            return new UsageInfoDto(classIri, 0, new ArrayList<>());
        }
    }
    
    private String parseAxiomForDisplay(String axiomText, String targetClassIri) {
        try {
            axiomText = axiomText.replace("<", "").replace(">", "");
            if (axiomText.startsWith("SubClassOf(")) {
                return parseSubClassOfAxiom(axiomText, targetClassIri);
            }
            if (axiomText.startsWith("EquivalentClasses(")) {
                return parseEquivalentClassesAxiom(axiomText, targetClassIri);
            }
            if (axiomText.startsWith("DisjointClasses(")) {
                return parseDisjointClassesAxiom(axiomText, targetClassIri);
            }
            if (axiomText.startsWith("Declaration(")) {
                return parseDeclarationAxiom(axiomText, targetClassIri);
            }
            return cleanAxiomText(axiomText);
        } catch (Exception e) {
            logger.warn("Failed to parse axiom: {}", axiomText, e);
            return cleanAxiomText(axiomText);
        }
    }
    private String parseSubClassOfAxiom(String axiomText, String targetClassIri) {
        String content = extractContent(axiomText, "SubClassOf(");
        String[] parts = splitAxiomParts(content);
        if (parts.length >= 2) {
            String subClass = resolveClassLabel(parts[0]);
            String superClass = parseClassExpression(parts[1]);
            return subClass + " SubClassOf " + superClass;
        }
        return cleanAxiomText(axiomText);
    }
    private String parseClassExpression(String expression) {
        expression = expression.trim();
        if (expression.contains("ObjectSomeValuesFrom(")) {
            String content = extractContent(expression, "ObjectSomeValuesFrom(");
            String[] parts = splitAxiomParts(content);
            if (parts.length >= 2) {
                String property = resolvePropertyLabel(parts[0]);
                String filler = resolveClassLabel(parts[1]);
                return property + " some " + filler;
            }
        }
        if (expression.contains("ObjectAllValuesFrom(")) {
            String content = extractContent(expression, "ObjectAllValuesFrom(");
            String[] parts = splitAxiomParts(content);
            if (parts.length >= 2) {
                String property = resolvePropertyLabel(parts[0]);
                String filler = resolveClassLabel(parts[1]);
                return property + " only " + filler;
            }
        }
        if (expression.contains("ObjectIntersectionOf(")) {
            String content = extractContent(expression, "ObjectIntersectionOf(");
            String[] parts = splitAxiomParts(content);
            List<String> resolved = new ArrayList<>();
            for (String part : parts) {
                resolved.add(parseClassExpression(part));
            }
            return "(" + String.join(" and ", resolved) + ")";
        }
        if (expression.contains("ObjectUnionOf(")) {
            String content = extractContent(expression, "ObjectUnionOf(");
            String[] parts = splitAxiomParts(content);
            List<String> resolved = new ArrayList<>();
            for (String part : parts) {
                resolved.add(parseClassExpression(part));
            }
            return "(" + String.join(" or ", resolved) + ")";
        }
        if (expression.contains("ObjectComplementOf(")) {
            String content = extractContent(expression, "ObjectComplementOf(");
            return "not " + parseClassExpression(content);
        }
        return resolveClassLabel(expression);
    }
    private String parseEquivalentClassesAxiom(String axiomText, String targetClassIri) {
        String content = extractContent(axiomText, "EquivalentClasses(");
        String[] parts = splitAxiomParts(content);
        if (parts.length >= 2) {
            String class1 = resolveClassLabel(parts[0]);
            String class2 = parseClassExpression(parts[1]);
            return class1 + " EquivalentTo " + class2;
        }
        return cleanAxiomText(axiomText);
    }
    private String parseDisjointClassesAxiom(String axiomText, String targetClassIri) {
        String content = extractContent(axiomText, "DisjointClasses(");
        String[] parts = splitAxiomParts(content);
        List<String> classes = new ArrayList<>();
        for (String part : parts) {
            classes.add(resolveClassLabel(part));
        }
        return String.join(" DisjointWith ", classes);
    }
    private String parseDeclarationAxiom(String axiomText, String targetClassIri) {
        String content = extractContent(axiomText, "Declaration(");
        return "Declaration";
    }
    private String extractContent(String text, String prefix) {
        int start = text.indexOf(prefix);
        if (start == -1) return text;
        start += prefix.length();
        int depth = 1;
        int end = start;
        while (end < text.length() && depth > 0) {
            char c = text.charAt(end);
            if (c == '(') depth++;
            if (c == ')') depth--;
            end++;
        }
        return text.substring(start, end - 1).trim();
    }
    private String[] splitAxiomParts(String content) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : content.toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ' ' && depth == 0) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts.toArray(new String[0]);
    }
    private String resolveClassLabel(String iri) {
        iri = iri.trim();
        iri = iri.replace("http://purl.obolibrary.org/obo/", "");
        iri = iri.replace("http://www.w3.org/2002/07/owl#", "owl:");
        if (iri.matches("[A-Z]+_\\d+")) {
            try {
                Query query = new Query(Criteria.where("localName").is(iri));
                ClassDocument classDoc = mongoTemplate.findOne(query, ClassDocument.class, "ontology_classes");
                if (classDoc != null) {
                    return getClassLabel(classDoc);
                }
            } catch (Exception e) {
                logger.debug("Could not resolve label for: {}", iri);
            }
        }
        return iri.replace("_", " ");
    }
    private String resolvePropertyLabel(String iri) {
        iri = iri.trim();
        iri = iri.replace("http://purl.obolibrary.org/obo/", "");
        iri = iri.replace("http://www.w3.org/2002/07/owl#", "owl:");
        Map<String, String> commonRelations = new HashMap<>();
        commonRelations.put("BFO_0000050", "part of");
        commonRelations.put("BFO_0000051", "has part");
        commonRelations.put("RO_0002131", "overlaps");
        commonRelations.put("RO_0002202", "develops from");
        if (commonRelations.containsKey(iri)) {
            return commonRelations.get(iri);
        }
        try {
            Query query = new Query(Criteria.where("localName").is(iri));
            PropertyDocument propDoc = mongoTemplate.findOne(query, PropertyDocument.class, "ontology_properties");
            if (propDoc != null) {
                return getPropertyLabel(propDoc);
            }
        } catch (Exception e) {
            logger.debug("Could not resolve property label for: {}", iri);
        }
        return iri.replace("_", " ");
    }
    private String cleanAxiomText(String text) {
        text = text.replace("http://purl.obolibrary.org/obo/", "");
        text = text.replace("http://www.w3.org/2002/07/owl#", "owl:");
        text = text.replace("_", " ");
        return text;
    }
    private String replaceIRIsWithShortForms(String text) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(https?://[^\\s<>]+[/#])([^\\s<>]+)");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String shortForm = matcher.group(2);
            shortForm = shortForm.replace("_", " ");
            matcher.appendReplacement(sb, shortForm);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
    private String categorizeAxiom(String axiomType, String axiomText) {
        if (axiomType == null) return "Other";
        if (axiomType.contains("SubClassOf")) {
            if (axiomText.contains("ObjectSomeValuesFrom") ||
                    axiomText.contains("ObjectAllValuesFrom") ||
                    axiomText.contains("some") ||
                    axiomText.contains("only")) {
                return "SubClassOf (Restriction)";
            }
            return "SubClassOf";
        } else if (axiomType.contains("EquivalentClasses")) {
            return "EquivalentTo";
        } else if (axiomType.contains("DisjointClasses")) {
            return "DisjointWith";
        } else if (axiomType.contains("Domain")) {
            return "Domain";
        } else if (axiomType.contains("Range")) {
            return "Range";
        } else if (axiomType.contains("ClassAssertion")) {
            return "Type";
        } else if (axiomType.contains("PropertyAssertion")) {
            return "Property Assertion";
        }
        return axiomType;
    }
    private String extractRelatedEntity(String axiomText, String excludeIri) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<?(https?://[^\\s<>]+)>?");
        java.util.regex.Matcher matcher = pattern.matcher(axiomText);
        while (matcher.find()) {
            String foundIri = matcher.group(1);
            if (!foundIri.equals(excludeIri) &&
                    !foundIri.startsWith("http://www.w3.org/2002/07/owl#") &&
                    !foundIri.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")) {
                return foundIri;
            }
        }
        return "";
    }
    private String getClassLabel(ClassDocument classDoc) {
        if (classDoc.getAnnotations() != null && classDoc.getAnnotations().get("label") != null) {
            return cleanLabel(classDoc.getAnnotations().get("label"));
        }
        return classDoc.getLocalName();
    }
    private String getPropertyLabel(PropertyDocument prop) {
        if (prop.getAnnotations() != null && prop.getAnnotations().get("label") != null) {
            return cleanLabel(prop.getAnnotations().get("label"));
        }
        return prop.getLocalName();
    }
    private String getIndividualLabel(IndividualDocument ind) {
        if (ind.getAnnotations() != null && ind.getAnnotations().get("label") != null) {
            return cleanLabel(ind.getAnnotations().get("label"));
        }
        return ind.getLocalName();
    }
    private String getShortForm(String iri) {
        if (iri.contains("#")) {
            return iri.substring(iri.lastIndexOf('#') + 1);
        } else if (iri.contains("/")) {
            return iri.substring(iri.lastIndexOf('/') + 1);
        }
        return iri;
    }

    /**
     * Generate OWL/RDF-XML from database contents for a project
     */
    public String generateOwlFromDatabase(String projectId) {
        logger.info("Generating OWL file from database for project: {}", projectId);
        
        try {
            Query metaQuery = new Query(Criteria.where("projectId").is(projectId));
            OntologyDocument ontologyDoc = mongoTemplate.findOne(metaQuery, OntologyDocument.class);
            
            if (ontologyDoc == null) {
                throw new RuntimeException("No ontology document found for project: " + projectId);
            }
            
            Query classQuery = new Query(Criteria.where("projectId").is(projectId));
            List<ClassDocument> classes = mongoTemplate.find(classQuery, ClassDocument.class, "ontology_classes");
            logger.info("Found {} classes for project {}", classes.size(), projectId);
            if (classes.isEmpty()) {
                logger.warn("NO CLASSES FOUND! Checking database...");
                long classCount = mongoTemplate.count(new Query(), "ontology_classes");
                logger.warn("Total classes in ontology_classes collection: {}", classCount);
            }
            
            Query propQuery = new Query(Criteria.where("projectId").is(projectId));
            List<PropertyDocument> properties = mongoTemplate.find(propQuery, PropertyDocument.class, "ontology_properties");
            
            Query indQuery = new Query(Criteria.where("projectId").is(projectId));
            List<IndividualDocument> individuals = mongoTemplate.find(indQuery, IndividualDocument.class, "ontology_individuals");
            
            Query annoPropQuery = new Query(Criteria.where("projectId").is(projectId));
            List<AnnotationPropertyDocument> annotationProperties = mongoTemplate.find(annoPropQuery, AnnotationPropertyDocument.class, "ontology_annotation_properties");
            
            StringBuilder owl = new StringBuilder();
            owl.append("<?xml version=\"1.0\"?>\n");
            String ontologyIRI = ontologyDoc.getMetadata() != null && ontologyDoc.getMetadata().getOntologyIRI() != null 
                ? ontologyDoc.getMetadata().getOntologyIRI() : "http://example.org/ontology";
            
            owl.append("<rdf:RDF xmlns=\"").append(ontologyIRI).append("#\"\n");
            owl.append("     xml:base=\"").append(ontologyIRI).append("\"\n");
            owl.append("     xmlns:owl=\"http://www.w3.org/2002/07/owl#\"\n");
            owl.append("     xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n");
            owl.append("     xmlns:xml=\"http://www.w3.org/XML/1998/namespace\"\n");
            owl.append("     xmlns:xsd=\"http://www.w3.org/2001/XMLSchema#\"\n");
            owl.append("     xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\">\n");
            
            owl.append("    <owl:Ontology rdf:about=\"").append(ontologyIRI).append("\">\n");
            if (ontologyDoc.getMetadata() != null && ontologyDoc.getMetadata().getVersionIRI() != null) {
                owl.append("        <owl:versionIRI rdf:resource=\"").append(ontologyDoc.getMetadata().getVersionIRI()).append("\"/>\n");
            }
            owl.append("    </owl:Ontology>\n\n");
            
            logger.info("Appending {} annotation properties to OWL", annotationProperties.size());
            for (AnnotationPropertyDocument annoProp : annotationProperties) {
                if (!annoProp.getIri().startsWith("http://www.w3.org/")) {
                    owl.append("    <owl:AnnotationProperty rdf:about=\"").append(annoProp.getIri()).append("\"/>\n");
                }
            }
            owl.append("\n");
            
            logger.info("Appending {} classes to OWL", classes.size());
            for (ClassDocument cls : classes) {
                if (!cls.getIri().contains("owl#Thing") && !cls.getIri().contains("rdf-syntax-ns#")) {
                    owl.append("    <owl:Class rdf:about=\"").append(cls.getIri()).append("\">\n");
                    
                    if (cls.getSuperClasses() != null && !cls.getSuperClasses().isEmpty()) {
                        for (String parentIri : cls.getSuperClasses()) {
                            owl.append("        <rdfs:subClassOf rdf:resource=\"").append(parentIri).append("\"/>\n");
                        }
                    }
                    
                    if (cls.getAnnotations() != null) {
                        for (Map.Entry<String, String> annotation : cls.getAnnotations().entrySet()) {
                            String propIri = annotation.getKey();
                            if (!propIri.startsWith("http://") && !propIri.startsWith("https://")) {
                                propIri = ontologyIRI + "#" + propIri;
                            }
                            owl.append("        <").append(getShortForm(propIri)).append(">")
                               .append(escapeXml(annotation.getValue()))
                               .append("</").append(getShortForm(propIri)).append(">\n");
                        }
                    }
                    
                    owl.append("    </owl:Class>\n");
                }
            }
            owl.append("\n");
            
            for (PropertyDocument prop : properties) {
                if ("ObjectProperty".equals(prop.getType())) {
                    owl.append("    <owl:ObjectProperty rdf:about=\"").append(prop.getIri()).append("\">\n");
                    
                    if (prop.getDomains() != null && !prop.getDomains().isEmpty()) {
                        for (String domain : prop.getDomains()) {
                            owl.append("        <rdfs:domain rdf:resource=\"").append(domain).append("\"/>\n");
                        }
                    }
                    if (prop.getRanges() != null && !prop.getRanges().isEmpty()) {
                        for (String range : prop.getRanges()) {
                            owl.append("        <rdfs:range rdf:resource=\"").append(range).append("\"/>\n");
                        }
                    }
                    
                    if (prop.getAnnotations() != null) {
                        for (Map.Entry<String, String> annotation : prop.getAnnotations().entrySet()) {
                            owl.append("        <").append(getShortForm(annotation.getKey())).append(">")
                               .append(escapeXml(annotation.getValue()))
                               .append("</").append(getShortForm(annotation.getKey())).append(">\n");
                        }
                    }
                    
                    owl.append("    </owl:ObjectProperty>\n");
                }
            }
            
            for (PropertyDocument prop : properties) {
                if ("DataProperty".equals(prop.getType())) {
                    owl.append("    <owl:DatatypeProperty rdf:about=\"").append(prop.getIri()).append("\">\n");
                    
                    if (prop.getDomains() != null && !prop.getDomains().isEmpty()) {
                        for (String domain : prop.getDomains()) {
                            owl.append("        <rdfs:domain rdf:resource=\"").append(domain).append("\"/>\n");
                        }
                    }
                    if (prop.getRanges() != null && !prop.getRanges().isEmpty()) {
                        for (String range : prop.getRanges()) {
                            owl.append("        <rdfs:range rdf:resource=\"").append(range).append("\"/>\n");
                        }
                    }
                    
                    if (prop.getAnnotations() != null) {
                        for (Map.Entry<String, String> annotation : prop.getAnnotations().entrySet()) {
                            owl.append("        <").append(getShortForm(annotation.getKey())).append(">")
                               .append(escapeXml(annotation.getValue()))
                               .append("</").append(getShortForm(annotation.getKey())).append(">\n");
                        }
                    }
                    
                    owl.append("    </owl:DatatypeProperty>\n");
                }
            }
            owl.append("\n");
            
            for (IndividualDocument ind : individuals) {
                String type = ind.getTypes() != null && !ind.getTypes().isEmpty() 
                    ? ind.getTypes().get(0) : "owl:NamedIndividual";
                
                owl.append("    <").append(getShortForm(type)).append(" rdf:about=\"").append(ind.getIri()).append("\">\n");
                
                if (ind.getTypes() != null) {
                    for (String typeIri : ind.getTypes()) {
                        if (!typeIri.equals(type)) {
                            owl.append("        <rdf:type rdf:resource=\"").append(typeIri).append("\"/>\n");
                        }
                    }
                }
                
                if (ind.getAnnotations() != null) {
                    for (Map.Entry<String, String> annotation : ind.getAnnotations().entrySet()) {
                        owl.append("        <").append(getShortForm(annotation.getKey())).append(">")
                           .append(escapeXml(annotation.getValue()))
                           .append("</").append(getShortForm(annotation.getKey())).append(">\n");
                    }
                }
                
                owl.append("    </").append(getShortForm(type)).append(">\n");
            }
            
            owl.append("</rdf:RDF>");
            
            logger.info("Successfully generated OWL file ({} bytes) for project: {}", owl.length(), projectId);
            return owl.toString();
            
        } catch (Exception e) {
            logger.error("Failed to generate OWL from database for project: {}", projectId, e);
            throw new RuntimeException("Failed to generate OWL file", e);
        }
    }
    
    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }
}
